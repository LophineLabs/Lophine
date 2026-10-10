// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads a continuous area, then executes only after Folia assigns every section to one actor.
 */
public final class CarpetRegionLease {
    private static final AtomicLong IDS = new AtomicLong();
    private static final TicketType<Long> HOLD = ChunkSystemTicketType.create("carpet:script_actor_area", Long::compare, 0L, TicketType.FLAG_LOADING);
    private static final Set<Lease<?>> ACTIVE = ConcurrentHashMap.newKeySet();
    // Admission and shutdown snapshots share ACTIVE's monitor; neither holds native locks.
    private static final carpet.script.external.WeakIdentityMap<MinecraftServer, Boolean> CLOSING = new carpet.script.external.WeakIdentityMap<>();
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon(true).name("Carpet region leases").unstarted(runnable));
    private static final int FULL = 33;

    private CarpetRegionLease() {
    }

    public static CompletableFuture<Void> run(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Consumer<Lease<Void>> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, 60_000L, lease -> {
            action.accept(lease);
            return null;
        });
    }

    public static <T> CompletableFuture<T> runValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, 60_000L, action);
    }

    public static <T> CompletableFuture<T> runValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long timeoutMillis, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, timeoutMillis, action, false);
    }

    /**
     * Runs a native phase immediately when its complete footprint is already owned and FULL.
     * Deferred phases must dispatch again and revalidate their own footprint; this path
     * retains their actual native lifetime but promises no chunk residency between phases.
     * General asynchronous consumers must use runValue's ticket-backed lifetime instead.
     */
    public static <T> CompletableFuture<T> runOwnedPhaseValue(ServerLevel world, int minChunkX, int minChunkZ,
                                                              int maxChunkX, int maxChunkZ, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, 60_000L, action, false, true);
    }

    /**
     * A native loaded-only query phase, with the same later-phase revalidation requirement.
     */
    public static <T> CompletableFuture<T> runOwnedLoadedPhaseValue(ServerLevel world, int minChunkX, int minChunkZ,
                                                                    int maxChunkX, int maxChunkZ, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, 60_000L, action, true, true);
    }

    /**
     * Merges the whole native topology but holds FULL tickets only on already loaded chunks.
     */
    public static <T> CompletableFuture<T> runLoadedValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, 60_000L, action, true);
    }

    public static <T> CompletableFuture<T> runLoadedValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long timeoutMillis, Function<Lease<T>, T> action) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, timeoutMillis, action, true);
    }

    private static <T> CompletableFuture<T> runValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long timeoutMillis, Function<Lease<T>, T> action, boolean loadedOnly) {
        return runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, timeoutMillis, action, loadedOnly, false);
    }

    private static <T> CompletableFuture<T> runValue(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                                     long timeoutMillis, Function<Lease<T>, T> action, boolean loadedOnly, boolean inlineOwnedPhase) {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ || timeoutMillis <= 0L)
            throw new IllegalArgumentException("Invalid region lease bounds or timeout");
        boolean inline = inlineOwnedPhase && TickThread.isTickThreadFor(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        if (inline && !loadedOnly) {
            for (long x = minChunkX; x <= maxChunkX && inline; x++)
                for (long z = minChunkZ; z <= maxChunkZ; z++)
                    if (world.getChunkIfLoaded((int) x, (int) z) == null) {
                        inline = false;
                        break;
                    }
        }
        MinecraftServer server = world.getServer();
        boolean nativeContinuation = carpet.script.external.ScarpetNativeWork.capture() != null;
        Lease<T> lease;
        synchronized (ACTIVE) {
            if (CLOSING.get(server) != null && !nativeContinuation)
                return CompletableFuture.failedFuture(new IllegalStateException("Server closed before Carpet area operation"));
            lease = new Lease<>(world, server, minChunkX, minChunkZ, maxChunkX, maxChunkZ, action, loadedOnly, nativeContinuation);
            try {
                ACTIVE.add(lease);
            } catch (Throwable failure) {
                ACTIVE.remove(lease);
                lease.cleanup.completeExceptionally(failure);
                throw failure;
            }
        }
        lease.future.whenComplete((value, failure) -> lease.finish());
        if (inline) {
            // No native ticket/scheduling locks are taken by the region actor. The
            // current actor protects this phase; later phases recheck their owners.
            lease.lifecycle.acquired();
            lease.pollOwner();
            return lease.future;
        }
        try {
            lease.deadline = TIMEOUTS.schedule(() -> {
                if (lease.lifecycle.cancelWaiting())
                    lease.future.completeExceptionally(new TimeoutException("Timed out loading or merging the Carpet operation area"));
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            Thread.ofVirtual().name("Carpet area loading").start(lease::acquire);
        } catch (Throwable failure) {
            lease.future.completeExceptionally(failure);
            // No acquisition thread started, so its usual finally cannot close admission.
            lease.lifecycle.acquired();
        }
        return lease.future;
    }

    /**
     * Close independent admissions before native drain, while preserving already accepted native continuations.
     */
    public static void beginShutdown(MinecraftServer server) {
        List<Lease<?>> waiting;
        synchronized (ACTIVE) {
            CLOSING.put(server, Boolean.TRUE);
            waiting = ACTIVE.stream().filter(lease -> lease.server == server && !lease.nativeContinuation).toList();
        }
        for (Lease<?> lease : waiting)
            if (lease.lifecycle.cancelWaiting())
                lease.future.completeExceptionally(new IllegalStateException("Server closed before Carpet area operation"));
    }

    /**
     * Cancels waiting work and releases its tickets without interrupting an in-progress actor.
     */
    public static void close(MinecraftServer server) {
        for (Lease<?> lease : ACTIVE)
            if (lease.server == server) {
                if (lease.lifecycle.cancelWaiting())
                    lease.future.completeExceptionally(new IllegalStateException("Server closed before Carpet area operation"));
                // Started work remains in ACTIVE until its actual actor and returned native stages end.
                // Runtime/NativeWork shutdown completes their real tails; no fake completion releases tickets.
            }
    }

    public static final class Lease<T> implements AutoCloseable {
        private final ServerLevel world;
        private final MinecraftServer server;
        private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        private final long id = IDS.incrementAndGet();
        private final Function<Lease<T>, T> action;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
        private final CarpetRegionLeaseLifecycle lifecycle = new CarpetRegionLeaseLifecycle(this::releaseTickets);
        private final List<Long> tickets = new ArrayList<>();
        private final List<Long> topology = new ArrayList<>();
        private final boolean loadedOnly;
        private final boolean nativeContinuation;
        private volatile ScheduledFuture<?> deadline;

        private Lease(ServerLevel world, MinecraftServer server, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Function<Lease<T>, T> action, boolean loadedOnly, boolean nativeContinuation) {
            this.world = world;
            this.server = server;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX;
            this.maxChunkZ = maxChunkZ;
            this.action = action;
            this.loadedOnly = loadedOnly;
            this.nativeContinuation = nativeContinuation;
            // Ticket/unpin cleanup runs after the actor's returned stage. Track it
            // globally before acquisition, without recording it into the actor it awaits.
            try {
                carpet.script.external.ScarpetNativeWork.trackNative(server, this.cleanup);
            } catch (Throwable failure) {
                // A failed caller-view allocation must also retire any already registered receipt.
                this.cleanup.completeExceptionally(failure);
                throw failure;
            }
        }

        public ServerLevel world() {
            return this.world;
        }

        public int minChunkX() {
            return this.minChunkX;
        }

        public int minChunkZ() {
            return this.minChunkZ;
        }

        public int maxChunkX() {
            return this.maxChunkX;
        }

        public int maxChunkZ() {
            return this.maxChunkZ;
        }

        public boolean ownsAll() {
            return TickThread.isTickThreadFor(this.world, this.minChunkX, this.minChunkZ, this.maxChunkX, this.maxChunkZ);
        }

        private void acquire() {
            try {
                var manager = this.world.moonrise$getChunkTaskScheduler().chunkHolderManager;
                if (loadedOnly) {
                    int shift = world.regioniser.sectionChunkShift;
                    for (long x = (long) minChunkX >> shift; x <= ((long) maxChunkX >> shift); x++)
                        for (long z = (long) minChunkZ >> shift; z <= ((long) maxChunkZ >> shift); z++) {
                            if (future.isDone()) return;
                            int chunkX = (int) (x << shift), chunkZ = (int) (z << shift);
                            long section = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
                            pinTopology(chunkX, chunkZ, true);
                            if (!lifecycle.appendTicket(() -> topology.add(section))) {
                                pinTopology(chunkX, chunkZ, false);
                                return;
                            }
                        }
                    for (long x = minChunkX; x <= maxChunkX; x++)
                        for (long z = minChunkZ; z <= maxChunkZ; z++) {
                            if (future.isDone()) return;
                            int chunkX = (int) x, chunkZ = (int) z;
                            if (CarpetLoadedChunkHold.tryAdd(world, chunkX, chunkZ, HOLD, id)) {
                                long chunk = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
                                if (!lifecycle.appendTicket(() -> tickets.add(chunk))) {
                                    CarpetLoadedChunkHold.remove(world, chunkX, chunkZ, HOLD, id);
                                    return;
                                }
                            }
                        }
                    this.enqueue();
                    return;
                }
                for (long x = this.minChunkX; x <= this.maxChunkX; ++x)
                    for (long z = this.minChunkZ; z <= this.maxChunkZ; ++z) {
                        if (this.future.isDone()) return;
                        final long chunk = net.minecraft.world.level.ChunkPos.pack((int) x, (int) z);
                        if (!this.lifecycle.appendTicket(() -> this.tickets.add(chunk))) return;
                        // No metadata monitor or actor waits for native ticket locks.
                        manager.addTicketAtLevel(HOLD, (int) x, (int) z, FULL, this.id);
                    }
                this.enqueue();
            } catch (Throwable failure) {
                this.future.completeExceptionally(failure);
            } finally {
                this.lifecycle.acquired();
            }
        }

        private void pinTopology(int x, int z, boolean add) {
            var scheduler = world.moonrise$getChunkTaskScheduler();
            var manager = scheduler.chunkHolderManager;
            int shift = world.regioniser.sectionChunkShift;
            int maxX = x + ((1 << shift) - 1), maxZ = z + ((1 << shift) - 1);
            var ticket = manager.ticketLockArea.lock(x, z, maxX, maxZ);
            try {
                var scheduling = scheduler.schedulingLockArea.lock(x, z, maxX, maxZ);
                try {
                    if (add) world.regioniser.carpetPinSection(x, z);
                    else world.regioniser.carpetUnpinSection(x, z);
                } finally {
                    scheduler.schedulingLockArea.unlock(scheduling);
                }
            } finally {
                manager.ticketLockArea.unlock(ticket);
            }
        }

        private void enqueue() {
            if (this.lifecycle.isClosed() || this.future.isDone()) return;
            try {
                this.world.getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE,
                        this.world.getWorld(), this.minChunkX, this.minChunkZ, this::pollOwner);
            } catch (Throwable failure) {
                this.future.completeExceptionally(failure);
            }
        }

        private void pollOwner() {
            if (this.lifecycle.isClosed() || this.future.isDone()) return;
            if (!this.ownsAll()) {
                this.retryNextTick();
                return;
            }
            if (!loadedOnly) for (long x = this.minChunkX; x <= this.maxChunkX; ++x)
                for (long z = this.minChunkZ; z <= this.maxChunkZ; ++z) {
                    if (this.world.getChunkIfLoaded((int) x, (int) z) == null) {
                        this.retryNextTick();
                        return;
                    }
                }
            if (!this.lifecycle.beginActor()) return;
            if (this.deadline != null) this.deadline.cancel(false);
            try {
                T result = this.action.apply(this);
                // The generic outer future still returns T, including a native CompletionStage.
                // Tickets belong to that real stage too, and to any nested stage it returns.
                this.lifecycle.follow(result);
                this.future.complete(result);
            } catch (Throwable failure) {
                this.future.completeExceptionally(failure);
            } finally {
                this.lifecycle.actorFinished();
            }
        }

        private void retryNextTick() {
            if (this.lifecycle.isClosed() || this.future.isDone()) return;
            try {
                this.world.getServer().server.getRegionScheduler().runDelayed(MinecraftInternalPlugin.INSTANCE,
                        this.world.getWorld(), this.minChunkX, this.minChunkZ, task -> this.pollOwner(), 1L);
            } catch (Throwable failure) {
                this.future.completeExceptionally(failure);
            }
        }

        private void finish() {
            this.lifecycle.close();
        }

        private void releaseTickets() {
            // The production lifecycle calls this only after acquire finally, actor finally,
            // and every returned native stage. No writer can still append a ticket here.
            List<Long> release = List.copyOf(this.tickets);
            this.tickets.clear();
            List<Long> unpin = List.copyOf(topology);
            topology.clear();
            if (this.deadline != null) this.deadline.cancel(false);
            if (release.isEmpty() && unpin.isEmpty()) {
                ACTIVE.remove(this);
                this.cleanup.complete(null);
                return;
            }
            Runnable cleanupTask = () -> {
                Throwable problem = null;
                try {
                    var manager = this.world.moonrise$getChunkTaskScheduler().chunkHolderManager;
                    for (long chunk : release) {
                        try {
                            manager.removeTicketAtLevel(HOLD, chunk, FULL, this.id);
                        } catch (Throwable failure) {
                            problem = appendFailure(problem, failure);
                            MinecraftServer.LOGGER.error("Could not release Carpet region lease chunk " + chunk, failure);
                        }
                    }
                } catch (Throwable failure) {
                    problem = appendFailure(problem, failure);
                    MinecraftServer.LOGGER.error("Could not access Carpet region lease ticket manager", failure);
                } finally {
                    for (long section : unpin)
                        try {
                            pinTopology(net.minecraft.world.level.ChunkPos.getX(section), net.minecraft.world.level.ChunkPos.getZ(section), false);
                        } catch (Throwable failure) {
                            problem = appendFailure(problem, failure);
                            MinecraftServer.LOGGER.error("Could not release Carpet topology section " + section, failure);
                        }
                    ACTIVE.remove(this);
                    if (problem == null) this.cleanup.complete(null);
                    else this.cleanup.completeExceptionally(problem);
                }
            };
            try {
                Thread.ofVirtual().name("Carpet area ticket release").start(cleanupTask);
            } catch (Throwable startFailure) {
                // The timeout executor is already running before a lease can acquire
                // tickets. It is a safe off-owner fallback when a new thread cannot start.
                try {
                    TIMEOUTS.execute(cleanupTask);
                } catch (Throwable fallbackFailure) {
                    Throwable failure = appendFailure(startFailure, fallbackFailure);
                    ACTIVE.remove(this);
                    this.cleanup.completeExceptionally(failure);
                    MinecraftServer.LOGGER.error("Could not dispatch Carpet region lease cleanup", failure);
                }
            }
        }

        private static Throwable appendFailure(Throwable previous, Throwable next) {
            if (previous == null) return next;
            if (previous != next) previous.addSuppressed(next);
            return previous;
        }

        @Override
        public void close() {
            if (!this.lifecycle.hasStarted() && this.lifecycle.cancelWaiting()) {
                this.future.completeExceptionally(new IllegalStateException("Carpet area lease closed"));
            }
            this.finish();
        }
    }
}
