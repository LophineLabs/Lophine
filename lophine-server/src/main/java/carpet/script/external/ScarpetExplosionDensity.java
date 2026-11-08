// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import fun.bm.lophine.carpet.CarpetRegionLease;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Exposure is a resumable owner phase. Native shapes run on the target owner;
 * block/cache reads revalidate the complete world footprint at every fill.
 * The budget is cooperative: a single native shape or a batch of at most 32
 * captures is not preempted. Sampling order and density match the native math
 * for a stable scene, while moving targets keep the bounding box captured once
 * at admission rather than restarting indefinitely after each yielded tick.
 */
public final class ScarpetExplosionDensity {
    private static final int OWNER_WORK_BUDGET = 2048;
    private static final long OWNER_TIME_BUDGET_NANOS = 2_000_000L;
    private static final int PREFETCH_BLOCKS = 32;

    public record Entry(BlockState state, VoxelShape shape) {
    }

    static final class Missing extends RuntimeException {
        final BlockPos position;

        Missing(BlockPos position) {
            super(null, null, false, false);
            this.position = position.immutable();
        }
    }

    static final class Snapshot implements BlockGetter {
        final Long2ObjectOpenHashMap<Entry> blocks = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<VoxelShape> shapeUpdates = new Long2ObjectOpenHashMap<>();
        final int height, minY;
        final Function<BlockPos, Entry> readOwned;

        Snapshot(int height, int minY, Function<BlockPos, Entry> readOwned) {
            this.height = height;
            this.minY = minY;
            this.readOwned = readOwned;
        }

        Entry entry(BlockPos position) {
            long key = position.asLong();
            Entry found = this.blocks.get(key);
            if (found == null) {
                found = this.readOwned.apply(position);
                if (found == null) throw new Missing(position);
                this.blocks.put(key, found);
            }
            return found;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            BlockState state = entry(position).state();
            return state == null ? Blocks.VOID_AIR.defaultBlockState() : state;
        }

        @Override
        public BlockState getBlockStateIfLoaded(BlockPos position) {
            return getBlockState(position);
        }

        @Override
        public FluidState getFluidIfLoaded(BlockPos position) {
            return getFluidState(position);
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            throw new IllegalStateException("A contextual explosion shape requested a foreign mutable block entity");
        }

        @Override
        public int getHeight() {
            return this.height;
        }

        @Override
        public int getMinY() {
            return this.minY;
        }
    }

    /**
     * Shared across all exposure jobs executed by the same region in one tick.
     */
    static final class Budget {
        final int maxWork;
        final long maxNanos;
        final LongSupplier clock;
        int work;
        long nanos;

        Budget(int maxWork, long maxNanos, LongSupplier clock) {
            this.maxWork = maxWork;
            this.maxNanos = maxNanos;
            this.clock = clock;
        }

        Slice slice() {
            return new Slice();
        }

        final class Slice implements AutoCloseable {
            final long start = clock.getAsLong();

            boolean available() {
                return work < maxWork && nanos + clock.getAsLong() - this.start < maxNanos;
            }

            boolean work() {
                if (!available()) return false;
                ++work;
                return true;
            }

            @Override
            public void close() {
                nanos += Math.max(0L, clock.getAsLong() - this.start);
            }
        }
    }

    private static final class OwnerBudget {
        WeakReference<Object> owner = new WeakReference<>(null);
        long tick;
        Budget budget;
    }

    private static final ThreadLocal<OwnerBudget> BUDGETS = ThreadLocal.withInitial(OwnerBudget::new);

    private static Budget ownerBudget() {
        if (ca.spottedleaf.moonrise.common.util.TickThread.isShutdownThread()) {
            // Accepted native tails drain on shutdown owners, where no next game tick is available.
            return new Budget(OWNER_WORK_BUDGET, OWNER_TIME_BUDGET_NANOS, System::nanoTime);
        }
        var region = TickRegionScheduler.getCurrentRegion();
        if (region == null) return new Budget(OWNER_WORK_BUDGET, OWNER_TIME_BUDGET_NANOS, System::nanoTime);
        var owner = region.getData().getRegionSchedulingHandle();
        long tick = owner.getCurrentTick();
        OwnerBudget saved = BUDGETS.get();
        if (saved.owner.get() != owner || saved.tick != tick) {
            saved.owner = new WeakReference<>(owner);
            saved.tick = tick;
            saved.budget = new Budget(OWNER_WORK_BUDGET, OWNER_TIME_BUDGET_NANOS, System::nanoTime);
        }
        return saved.budget;
    }

    interface Backend {
        <T> CompletableFuture<T> entity(Supplier<T> action, boolean later);

        CompletableFuture<Boolean> read(Snapshot snapshot, List<BlockPos> positions, boolean later);

        CompletableFuture<Float> store(Snapshot snapshot, float density);

        Budget budget();
    }

    private record Step(boolean done, BlockPos missing) {
    }

    /**
     * Serial trampoline: already-completed owner futures must not build a recursive Java stack.
     */
    static final class Job {
        final CompletableFuture<Float> result = new CompletableFuture<>();
        final ScarpetExplosionSampler sampler;
        final Snapshot snapshot;
        final Backend backend;
        final ScarpetExplosionSampler.Probe probe;
        private final ConcurrentLinkedQueue<Runnable> continuations = new ConcurrentLinkedQueue<>();
        private final AtomicInteger draining = new AtomicInteger();

        Job(ScarpetExplosionSampler sampler, Snapshot snapshot, Backend backend, ScarpetExplosionSampler.Probe probe) {
            this.sampler = sampler;
            this.snapshot = snapshot;
            this.backend = backend;
            this.probe = probe;
        }

        void start() {
            enqueue(() -> sample(false));
        }

        private void enqueue(Runnable action) {
            Supplier<Void> captured = ScarpetRuntime.captureNativeContinuation(() -> {
                action.run();
                return null;
            });
            this.continuations.add(() -> captured.get());
            if (this.draining.getAndIncrement() != 0) return;
            int missed = 1;
            do {
                Runnable next;
                while ((next = this.continuations.poll()) != null) {
                    if (this.result.isDone()) continue;
                    try {
                        next.run();
                    } catch (Throwable failure) {
                        this.result.completeExceptionally(failure);
                    }
                }
                missed = this.draining.addAndGet(-missed);
            } while (missed != 0);
        }

        private <T> void follow(CompletableFuture<T> phase, java.util.function.Consumer<T> next) {
            phase.whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) -> enqueue(() -> {
                if (failure != null) this.result.completeExceptionally(failure);
                else next.accept(value);
            })));
        }

        private void sample(boolean later) {
            follow(this.backend.entity(() -> {
                try (var slice = this.backend.budget().slice()) {
                    try {
                        return new Step(this.sampler.advance(slice::work, this.probe), null);
                    } catch (Missing missing) {
                        return new Step(false, missing.position);
                    }
                }
            }, later), step -> {
                if (step.done()) {
                    follow(this.backend.store(this.snapshot, this.sampler.result()), this.result::complete);
                } else if (step.missing() != null) {
                    var requests = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockPos>();
                    requests.put(step.missing().asLong(), step.missing());
                    for (BlockPos position : this.sampler.lookahead(PREFETCH_BLOCKS - 1)) {
                        if (!this.snapshot.blocks.containsKey(position.asLong()))
                            requests.put(position.asLong(), position);
                    }
                    read(List.copyOf(requests.values()), false);
                } else sample(true);
            });
        }

        private void read(List<BlockPos> positions, boolean later) {
            follow(this.backend.read(this.snapshot, positions, later), loaded -> {
                if (loaded) sample(false);
                else read(positions, true);
            });
        }
    }

    private ScarpetExplosionDensity() {
    }

    public static CompletableFuture<Float> compute(ServerExplosion explosion, Entity target) {
        // Local loaded geometry needs no owner hand-off or per-tick sampling delay.
        // The explosion revalidates the entire ray footprint before using native sampling.
        if (TickThread.isTickThreadFor(target)) {
            Float owned = explosion.carpetTryOwnedDensity(target);
            if (owned != null) return CompletableFuture.completedFuture(owned);
        }
        // One immutable geometry snapshot, as in the native synchronous getSeenFraction.
        // Chasing a moving bounding box across yielded ticks would never terminate.
        CompletableFuture<Float> actual = ScarpetExplosionActors.entity(target, target::getBoundingBox).thenCompose(ScarpetRuntime.captureNativeFunction(bounds ->
                ScarpetExplosionActors.world(explosion.level(), BlockPos.containing(explosion.center()), () ->
                        explosion.carpetCachedDensity(bounds)).thenCompose(ScarpetRuntime.captureNativeFunction(cached -> {
                    if (cached != null) return CompletableFuture.completedFuture(cached);
                    Backend backend = new NativeBackend(explosion, target, bounds);
                    BlockPos center = BlockPos.containing(explosion.center());
                    Snapshot snapshot = new Snapshot(explosion.level().getHeight(), explosion.level().getMinY(),
                            position -> readOwned(explosion, center, position));
                    CollisionUtil.LazyEntityCollisionContext[] context = new CollisionUtil.LazyEntityCollisionContext[1];
                    var job = new Job(new ScarpetExplosionSampler(bounds, explosion.center()), snapshot, backend, (from, to, position) -> {
                        Entry entry = snapshot.entry(position);
                        BlockState state = entry.state();
                        if (state == null || ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState) state).moonrise$emptyContextCollisionShape())
                            return false;
                        VoxelShape collision = entry.shape();
                        if (collision == null) {
                            if (context[0] == null) context[0] = new CollisionUtil.LazyEntityCollisionContext(target);
                            collision = state.getCollisionShape(snapshot, position, context[0]);
                            if (!context[0].isDelegated()) snapshot.shapeUpdates.put(position.asLong(), collision);
                        }
                        return !collision.isEmpty() && collision.clip(from, to, position) != null;
                    });
                    job.start();
                    return job.result;
                }))));
        ScarpetNativeWork.record(actual);
        return ScarpetNativeWork.trackNative(explosion.level().getServer(), actual);
    }

    // The current target actor can also fill its snapshot when it owns the explosion
    // cache and the complete loaded footprint between the center and the requested block.
    // A foreign or unloaded footprint still goes through the ticket-backed lease.
    static Entry readOwned(ServerExplosion explosion, BlockPos center, BlockPos position) {
        var level = explosion.level();
        int minX = Math.min(center.getX(), position.getX()) >> 4, maxX = Math.max(center.getX(), position.getX()) >> 4;
        int minZ = Math.min(center.getZ(), position.getZ()) >> 4, maxZ = Math.max(center.getZ(), position.getZ()) >> 4;
        if (!TickThread.isTickThreadFor(level, minX, minZ, maxX, maxZ)) return null;
        for (int x = minX; x <= maxX; ++x) {
            for (int z = minZ; z <= maxZ; ++z) {
                if (level.getChunkIfLoaded(x, z) == null) return null;
            }
        }
        return explosion.carpetCollisionEntry(position);
    }

    private record NativeBackend(ServerExplosion explosion, Entity target,
                                 net.minecraft.world.phys.AABB bounds) implements Backend {
        @Override
        public <T> CompletableFuture<T> entity(Supplier<T> action, boolean later) {
            return later ? ScarpetExplosionActors.entityLater(this.target, action) : ScarpetExplosionActors.entity(this.target, action);
        }

        @Override
        public Budget budget() {
            return ownerBudget();
        }

        @Override
        public CompletableFuture<Float> store(Snapshot snapshot, float density) {
            return ScarpetExplosionActors.world(this.explosion.level(), BlockPos.containing(this.explosion.center()), () -> {
                this.explosion.carpetStoreDensity(this.bounds, density, snapshot.shapeUpdates);
                return density;
            });
        }

        @Override
        public CompletableFuture<Boolean> read(Snapshot snapshot, List<BlockPos> positions, boolean later) {
            if (later) {
                return ScarpetExplosionActors.worldLater(this.explosion.level(), BlockPos.containing(this.explosion.center()), () -> null)
                        .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> read(snapshot, positions, false)));
            }
            BlockPos center = BlockPos.containing(this.explosion.center());
            int minX = center.getX() >> 4, maxX = minX, minZ = center.getZ() >> 4, maxZ = minZ;
            for (BlockPos position : positions) {
                minX = Math.min(minX, position.getX() >> 4);
                maxX = Math.max(maxX, position.getX() >> 4);
                minZ = Math.min(minZ, position.getZ() >> 4);
                maxZ = Math.max(maxZ, position.getZ() >> 4);
            }
            Supplier<Boolean> owned = ScarpetRuntime.captureNativeContinuation(() -> {
                Budget budget = ownerBudget();
                try (var slice = budget.slice()) {
                    if (!slice.available()) return false;
                    // At most 32 reads; include them in the shared budget, allowing one bounded batch of overshoot.
                    for (BlockPos position : positions) {
                        ++budget.work;
                        snapshot.blocks.put(position.asLong(), this.explosion.carpetCollisionEntry(position));
                    }
                    return true;
                }
            });
            CompletableFuture<Boolean> done = CarpetRegionLease.runOwnedPhaseValue(this.explosion.level(), minX, minZ, maxX, maxZ, lease -> owned.get());
            ScarpetNativeWork.record(done);
            return done;
        }
    }
}
