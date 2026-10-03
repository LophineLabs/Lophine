// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRetiredActors;
import carpet.script.external.ScarpetRuntime;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

/** Real sequential saved-entity restoration; every actor, native callback and FULL hold is awaited. */
public final class CarpetPlayerSpawnContinuations {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<Entity, Integer> PAUSED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final MapCodec<CompoundTag> COPY = new MapCodec<>() {
        @Override public <T> DataResult<CompoundTag> decode(DynamicOps<T> ops, MapLike<T> input) {
            return CompoundTag.CODEC.parse(ops, ops.createMap(input.entries())).map(CompoundTag::copy);
        }
        @Override public <T> RecordBuilder<T> encode(CompoundTag input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return prefix.withErrorsFrom(DataResult.error(() -> "Saved player input snapshot is read-only"));
        }
        @Override public <T> Stream<T> keys(DynamicOps<T> ops) { return Stream.empty(); }
    };

    private CarpetPlayerSpawnContinuations() { }

    /** Actor schedulers remain active while ordinary physics of the restored tree is paused. */
    public static boolean pending(Entity entity) { synchronized (PAUSED) { return PAUSED.containsKey(entity); } }

    /** Metadata only: accepted newborn physics follows the private true native lifetime, while its actor scheduler remains active. */
    public static void holdUntil(Entity entity, CompletableFuture<?> actualNativeReceipt) {
        synchronized (PAUSED) { PAUSED.merge(entity, 1, Integer::sum); }
        actualNativeReceipt.whenComplete((ignored, failure) -> {
            synchronized (PAUSED) { PAUSED.computeIfPresent(entity, (key, count) -> count == 1 ? null : count - 1); }
        });
    }


    public static CompletableFuture<Void> extras(ServerPlayer player, ValueInput input) {
        Snapshot snapshot = snapshot(input);
        return submit(player, job -> then(pearls(player, snapshot, job), ignored -> parent(player, snapshot, job)));
    }
    public static CompletableFuture<Void> pearls(ServerPlayer player, ValueInput input) {
        Snapshot snapshot = snapshot(input);
        return submit(player, job -> pearls(player, snapshot, job));
    }
    public static CompletableFuture<Void> parent(ServerPlayer player, ValueInput input) {
        Snapshot snapshot = snapshot(input);
        return submit(player, job -> parent(player, snapshot, job));
    }

    private static Snapshot snapshot(ValueInput input) {
        CompoundTag tag = input instanceof TagValueInput nativeInput ? nativeInput.input.copy()
            : input.read(COPY).orElseThrow(() -> new IllegalArgumentException("Player restoration input cannot be copied"));
        return new Snapshot(tag, input.lookup());
    }

    /** The private actual job is registered before even the initial owner task is queued. */
    private static CompletableFuture<Void> submit(ServerPlayer player, Function<Job, CompletableFuture<Void>> body) {
        Job job = new Job();
        CompletableFuture<Void> actual = new CompletableFuture<>();
        ScarpetNativeWork.record(actual);
        CompletableFuture<Void> caller = ScarpetNativeWork.trackNative(player.carpetSpawnServer(), actual);
        actual.whenComplete((ignored, failure) -> job.releaseAfterParent());
        try {
            var owner = ScarpetExplosionActors.entity(player, () -> {
                var observed = ScarpetNativeWork.observeNative(player, () -> {
                    try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                        CompletableFuture<Void> sequence = body.apply(job);
                        ScarpetNativeWork.record(sequence);
                        return null;
                    }
                });
                ScarpetNativeWork.aliasDependency(actual, observed);
                return observed;
            });
            owner.thenCompose(value -> value).whenComplete((ignored, failure) -> complete(actual, failure));
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        return caller;
    }

    private static CompletableFuture<Void> pearls(ServerPlayer player, Snapshot snapshot, Job job) {
        var inputs = snapshot.input().childrenListOrEmpty("ender_pearls");
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
        // Freeze the inputs before leaving the player actor; each element has its own native phase.
        for (ValueInput value : inputs) {
            Snapshot pearl = snapshot(value);
            sequence = then(sequence, ignored -> pearl(player, pearl, job));
        }
        return sequence;
    }

    private static CompletableFuture<Void> pearl(ServerPlayer player, Snapshot saved, Job job) {
        ValueInput input = saved.input();
        var dimension = input.read("ender_pearl_dimension", Level.RESOURCE_KEY_CODEC);
        if (dimension.isEmpty()) return CompletableFuture.completedFuture(null);
        // MinecraftServer's dimension registry is stable during an admitted native birth job.
        ServerLevel world = player.carpetSpawnServer().getLevel(dimension.get());
        if (world == null) {
            LOGGER.warn("Trying to load ender pearl without level ({}) being loaded, skipping", dimension.get());
            return CompletableFuture.completedFuture(null);
        }
        Bounds bounds = Bounds.tree(saved);
        return full(world, bounds, () -> then(detached(world, saved, job), root -> {
            if (root == null) {
                LOGGER.warn("Failed to spawn player ender pearl in level ({}), skipping", dimension.get());
                return CompletableFuture.completedFuture(null);
            }
            return then(world(world, bounds.center(), () -> root.getSelfAndPassengers().toList()), entities -> {
                CompletableFuture<Void> add = CompletableFuture.completedFuture(null);
                for (Entity entity : entities) add = then(add, ignored -> world(world, bounds.center(), () -> {
                    return world.carpetAddFreshEntityNativeAsync(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT);
                }).thenCompose(value -> value).thenApply(added -> null));
                return then(add, ignored -> entity(root, () -> {
                    // A plugin may have moved the just-added pearl. Renew in its actual final world/owner.
                    if (root.level() instanceof ServerLevel finalWorld) ServerPlayer.placeEnderPearlTicket(finalWorld, root.chunkPosition());
                    return null;
                }));
            });
        }));
    }

    /** Load one tag, then wait every passenger's load/mount children before loading the next tag. */
    private static CompletableFuture<Entity> detached(ServerLevel world, Snapshot saved, Job job) {
        ValueInput input = saved.input();
        BlockPos position = position(input);
        CompoundTag single = saved.tag().copy(); single.remove("Passengers");
        return then(world(world, position, () -> {
            Entity entity = EntityType.loadEntityRecursive(saved.with(single).input(), world, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity != null) { job.hold(entity); ScarpetRetiredActors.capture(entity); }
            return entity;
        }), root -> {
            if (root == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> passengers = CompletableFuture.completedFuture(null);
            for (ValueInput value : input.childrenListOrEmpty("Passengers")) {
                Snapshot child = snapshot(value);
                passengers = then(passengers, ignored -> then(detached(world, child, job), passenger -> passenger == null
                    ? CompletableFuture.completedFuture(null)
                    : then(ride(passenger, root, 0), ignoredRide -> CompletableFuture.completedFuture(null))));
            }
            return then(passengers, ignored -> CompletableFuture.completedFuture(root));
        });
    }

    private static CompletableFuture<Void> parent(ServerPlayer player, Snapshot snapshot, Job job) {
        var rootInput = snapshot.input().child("RootVehicle");
        if (rootInput.isEmpty()) return CompletableFuture.completedFuture(null);
        Snapshot vehicle = snapshot(rootInput.get());
        // All native placement positions end within five blocks of this real player position.
        return then(entity(player, () -> new Placement(player.level(), player.position())), placement ->
            full(placement.world(), Bounds.around(placement.position(), 8), () ->
                then(parentNode(placement, snapshot(vehicle.input().childOrEmpty("Entity")), job), root -> {
                    if (root == null) return CompletableFuture.completedFuture(null);
                    var attach = vehicle.input().read("Attach", UUIDUtil.CODEC).orElse(null);
                    return then(entity(root, () -> root.getSelfAndPassengers().toList()), tree -> {
                        Entity selected = null;
                        for (Entity entity : tree) if (entity.getUUID().equals(attach)) { selected = entity; break; }
                        var attempted = selected == null ? CompletableFuture.completedFuture(false) : ride(player, selected, 0);
                        return then(attempted, ignoredAttempt -> then(entity(player, player::isPassenger), riding -> {
                        if (riding) return CompletableFuture.completedFuture(null);
                        LOGGER.warn("Couldn't reattach entity to player");
                        return then(entity(root, () -> {
                            List<Entity> discarded = new ArrayList<>(); discarded.add(root);
                            root.getIndirectPassengers().forEach(discarded::add); return List.copyOf(discarded);
                        }), discarded -> {
                            CompletableFuture<Void> remove = CompletableFuture.completedFuture(null);
                            for (Entity entity : discarded) remove = then(remove, ignored -> entity(entity, () -> { entity.discard(null); return null; }));
                            return remove;
                        });
                        }));
                    });
                })));
    }

    private static CompletableFuture<Entity> parentNode(Placement placement, Snapshot saved, Job job) {
        BlockPos original = position(saved.input());
        CompoundTag single = saved.tag().copy(); single.remove("Passengers");
        // The original saved position can be far away: construct in its own small FULL area,
        // then move the still-unadded entity into the held target area on that area's actor.
        return then(full(placement.world(), Bounds.around(Vec3.atCenterOf(original), 2), () -> world(placement.world(), original, () -> {
            Entity entity = EntityType.loadEntityRecursive(saved.with(single).input(), placement.world(), EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity != null) { job.hold(entity); ScarpetRetiredActors.capture(entity); }
            return entity;
        })), root -> {
            if (root == null) return CompletableFuture.completedFuture(null);
            BlockPos target = BlockPos.containing(placement.position());
            return then(world(placement.world(), target, () -> {
                if (root.distanceToSqr(placement.position()) > 25.0D) root.setPosRaw(placement.position().x, placement.position().y, placement.position().z, true);
                TisLifetimeTracker.markSpawn(root, "player_login");
                ScarpetRetiredActors.capture(root);
                return placement.world().carpetAddWithUUIDNativeAsync(root, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.MOUNT);
            }).thenCompose(value -> value), added -> {
                if (!added) return CompletableFuture.completedFuture(null);
                CompletableFuture<Void> passengers = CompletableFuture.completedFuture(null);
                for (ValueInput value : saved.input().childrenListOrEmpty("Passengers")) {
                    Snapshot child = snapshot(value);
                    passengers = then(passengers, ignored -> then(parentNode(placement, child, job), passenger -> passenger == null
                        ? CompletableFuture.completedFuture(null)
                        : then(ride(passenger, root, 0), ignoredRide -> CompletableFuture.completedFuture(null))));
                }
                return then(passengers, ignored -> CompletableFuture.completedFuture(root));
            });
        });
    }

    private static <T> CompletableFuture<T> full(ServerLevel world, Bounds bounds, Supplier<CompletableFuture<T>> body) {
        Supplier<CompletableFuture<T>> captured = ScarpetRuntime.captureNativeContinuation(body);
        CompletableFuture<CompletableFuture<T>> held = CarpetRegionLease.<CompletableFuture<T>>runValue(world, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), lease -> captured.get());
        var result = held.thenCompose(value -> value); ScarpetNativeWork.record(result); return result;
    }
    private static <T> CompletableFuture<T> world(ServerLevel world, BlockPos position, Supplier<T> body) {
        var actor = ScarpetExplosionActors.world(world, position, () ->
            ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(null, body)));
        var actual = actor.thenCompose(observed -> observed); ScarpetNativeWork.record(actual); return actual;
    }
    private static <T> CompletableFuture<T> entity(Entity entity, Supplier<T> body) {
        var actor = ScarpetExplosionActors.entity(entity, () ->
            ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(entity, body)));
        var actual = actor.thenCompose(observed -> observed); ScarpetNativeWork.record(actual); return actual;
    }
    /** Mount needs both real owners, even if an add callback teleported one restored entity. */
    private static CompletableFuture<Boolean> ride(Entity rider, Entity vehicle, int attempts) {
        if (attempts == 8) return CompletableFuture.failedFuture(new IllegalStateException("Restored vehicle kept changing its owner"));
        return then(entity(rider, () -> new Placement((ServerLevel)rider.level(), rider.position())), riderPosition ->
            then(entity(vehicle, () -> new Placement((ServerLevel)vehicle.level(), vehicle.position())), vehiclePosition -> {
                if (riderPosition.world() != vehiclePosition.world()) return CompletableFuture.completedFuture(false);
                Bounds first = Bounds.around(riderPosition.position(), 2), second = Bounds.around(vehiclePosition.position(), 2);
                Bounds area = new Bounds(Math.min(first.minX, second.minX), Math.min(first.minZ, second.minZ), Math.max(first.maxX, second.maxX), Math.max(first.maxZ, second.maxZ));
                ServerLevel world = riderPosition.world();
                var captured = ScarpetRuntime.captureNativeContinuation(() -> world(world, BlockPos.containing(riderPosition.position()), () -> {
                    if (!TickThread.isTickThreadFor(rider) || !TickThread.isTickThreadFor(vehicle)) return new RideResult(false, false);
                    if (rider.level() != world || vehicle.level() != world) return new RideResult(false, false);
                    return new RideResult(true, rider.startRiding(vehicle, true, false));
                }));
                CompletableFuture<CompletableFuture<RideResult>> held = CarpetRegionLease.runLoadedValue(world, area.minX, area.minZ, area.maxX, area.maxZ, lease -> captured.get());
                var mounted = held.thenCompose(value -> value); ScarpetNativeWork.record(mounted);
                return then(mounted, result -> result.owned ? CompletableFuture.completedFuture(result.mounted) : ride(rider, vehicle, attempts + 1));
            }));
    }
    /** CF callbacks restore the complete native flags/token before creating their next observer. */
    private static <T, R> CompletableFuture<R> then(CompletableFuture<T> before, Function<T, CompletableFuture<R>> next) {
        var result = before.thenCompose(ScarpetRuntime.captureNativeFunction(next));
        ScarpetNativeWork.record(result); return result;
    }
    private static void complete(CompletableFuture<Void> actual, Throwable failure) {
        if (failure == null) actual.complete(null); else actual.completeExceptionally(failure);
    }
    private static BlockPos position(ValueInput input) {
        Vec3 pos = input.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
        return BlockPos.containing(net.minecraft.util.Mth.clamp(pos.x, -3.0000512E7, 3.0000512E7),
            net.minecraft.util.Mth.clamp(pos.y, -2.0E7, 2.0E7), net.minecraft.util.Mth.clamp(pos.z, -3.0000512E7, 3.0000512E7));
    }
    private record Snapshot(CompoundTag tag, HolderLookup.Provider lookup) {
        ValueInput input() { return TagValueInput.create(ProblemReporter.DISCARDING, lookup, tag); }
        Snapshot with(CompoundTag value) { return new Snapshot(value, lookup); }
    }
    private record Placement(ServerLevel world, Vec3 position) { }
    private record RideResult(boolean owned, boolean mounted) { }
    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
        BlockPos center() { return new BlockPos(minX << 4, 0, minZ << 4); }
        static Bounds around(Vec3 position, int radius) {
            return new Bounds((net.minecraft.util.Mth.floor(position.x) - radius) >> 4, (net.minecraft.util.Mth.floor(position.z) - radius) >> 4,
                (net.minecraft.util.Mth.floor(position.x) + radius) >> 4, (net.minecraft.util.Mth.floor(position.z) + radius) >> 4);
        }
        static Bounds tree(Snapshot snapshot) {
            BlockPos pos = position(snapshot.input()); Bounds result = around(Vec3.atCenterOf(pos), 2);
            for (ValueInput input : snapshot.input().childrenListOrEmpty("Passengers")) {
                Bounds child = tree(snapshot(input));
                result = new Bounds(Math.min(result.minX, child.minX), Math.min(result.minZ, child.minZ), Math.max(result.maxX, child.maxX), Math.max(result.maxZ, child.maxZ));
            }
            return result;
        }
    }
    private static final class Job {
        private final ScarpetNativeWork.Token parent = ScarpetNativeWork.capture();
        private final Set<Entity> held = Collections.newSetFromMap(new IdentityHashMap<>());
        void hold(Entity entity) { synchronized (PAUSED) { if (held.add(entity)) PAUSED.merge(entity, 1, Integer::sum); } }
        // A BotList/native login driver still has init/Join tails after extras. This is metadata
        // only: recording the parent's own completion into its child would create a cycle.
        void releaseAfterParent() {
            if (parent == null) release();
            else ScarpetNativeWork.completionOf(parent).whenComplete((ignored, failure) -> release());
        }
        void release() { synchronized (PAUSED) {
            for (Entity entity : held) { int count = PAUSED.getOrDefault(entity, 0); if (count <= 1) PAUSED.remove(entity); else PAUSED.put(entity, count - 1); }
            held.clear();
        } }
    }
}
