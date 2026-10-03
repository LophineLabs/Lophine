// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Native attack tails use the actual damage outcome before reading health or applying item effects.
 */
public final class ScarpetAttackContinuations {
    private record Location(ServerLevel world, BlockPos position) {
    }

    private static final WeakIdentityMap<net.minecraft.world.entity.LivingEntity, CompletableFuture<Boolean>> STABS = new WeakIdentityMap<>();

    private ScarpetAttackContinuations() {
    }

    private static final WeakIdentityMap<net.minecraft.world.entity.LivingEntity, CompletableFuture<Boolean>> HITS = new WeakIdentityMap<>();

    public static CompletableFuture<Boolean> pendingHitResult(net.minecraft.world.entity.LivingEntity attacker) {
        return HITS.get(attacker);
    }

    public static void publishHit(net.minecraft.world.entity.LivingEntity attacker, CompletableFuture<Boolean> actual) {
        HITS.put(attacker, actual);
    }

    public static CompletableFuture<Boolean> pendingStabResult(net.minecraft.world.entity.LivingEntity attacker) {
        return STABS.get(attacker);
    }

    public static void publishStab(net.minecraft.world.entity.LivingEntity attacker, CompletableFuture<Boolean> actual) {
        STABS.put(attacker, actual);
    }

    public static CompletableFuture<Boolean> pendingStabResult(Player attacker) {
        return STABS.get(attacker);
    }

    public static void publishStab(Player attacker, CompletableFuture<Boolean> actual) {
        STABS.put(attacker, actual);
        // Source callers compare before/after receipts immediately after the native call returns.
        // Retain an already completed real bool too; the next source operation replaces it and the key is weak.
    }

    public static void queueStab(ServerPlayer attacker, java.util.function.BooleanSupplier nativeStab) {
        var actual = ScarpetPlayerInventoryGate.enqueuePaused(attacker, () -> {
            var before = pendingStabResult(attacker);
            boolean immediate = nativeStab.getAsBoolean();
            var after = pendingStabResult(attacker);
            return after != null && after != before ? after : CompletableFuture.completedFuture(immediate);
        });
        publishStab(attacker, actual);
    }

    public static CompletableFuture<Void> sequence(ServerPlayer attacker, java.util.List<? extends Entity> targets,
                                                   Function<Entity, CompletableFuture<Void>> nativeHit, Runnable finished) {
        return ScarpetNativeWork.observeNative(attacker, () -> {
            CompletableFuture<Void> sequence = sequenceFrom(attacker, targets, 0, nativeHit, finished);
            ScarpetNativeWork.record(sequence);
            return sequence;
        }).thenCompose(Function.identity());
    }

    private static CompletableFuture<Void> sequenceFrom(ServerPlayer attacker, java.util.List<? extends Entity> targets,
                                                        int index, Function<Entity, CompletableFuture<Void>> nativeHit, Runnable finished) {
        for (int cursor = index; cursor < targets.size(); cursor++) {
            Entity target = targets.get(cursor);
            CompletableFuture<Void> hit;
            if (TickThread.isTickThreadFor(attacker) && TickThread.isTickThreadFor(target))
                hit = nativeHit.apply(target);
            else {
                var captured = ScarpetRuntime.captureNativeContinuation(() -> nativeHit.apply(target));
                hit = jointlyOwned(attacker, target, captured, 0).thenCompose(Function.identity());
            }
            if (hit.isDone() && !hit.isCompletedExceptionally() && !hit.isCancelled()) continue;
            int next = cursor + 1;
            var captured = ScarpetRuntime.captureNativeContinuation(() -> {
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(attacker)) {
                    return sequenceFrom(attacker, targets, next, nativeHit, finished);
                }
            });
            return hit.thenCompose(ignored -> ScarpetExplosionActors.entity(attacker, captured).thenCompose(Function.identity()));
        }
        finished.run();
        return CompletableFuture.completedFuture(null);
    }

    public static <T> CompletableFuture<T> afterDamage(ServerPlayer attacker, Entity target,
                                                       CompletableFuture<Boolean> outcome, Function<Boolean, T> nativeTail) {
        return afterDamage((Entity) attacker, target, outcome, nativeTail);
    }

    /**
     * Runs a vanilla damage tail only after the real hit result is known, with both actors owned by the current region.
     * This overload also covers non-player mob attacks whose victim has a Scarpet damage callback.
     */
    public static <T> CompletableFuture<T> afterDamage(Entity attacker, Entity target,
                                                       CompletableFuture<Boolean> outcome, Function<Boolean, T> nativeTail) {
        TickThread.ensureTickThread(attacker, "Deferred attack must be captured by its attacker");
        var completed = new CompletableFuture<T>();
        CompletableFuture<CompletableFuture<T>> actual = ScarpetNativeWork.observeNative(attacker, () -> {
            var token = ScarpetNativeWork.capture();
            ScarpetNativeWork.record(completed);
            Function<Boolean, T> tail = hurt -> ScarpetNativeWork.with(token, () -> withAcceptedPlayer(attacker, () -> nativeTail.apply(hurt)));
            var resolved = new java.util.concurrent.atomic.AtomicReference<Boolean>();
            var captured = ScarpetRuntime.captureNativeContinuation(() -> tail.apply(Boolean.TRUE.equals(resolved.get())));
            outcome.whenComplete((hurt, failure) -> {
                if (failure != null) {
                    completed.completeExceptionally(failure);
                    return;
                }
                resolved.set(hurt);
                jointlyOwned(attacker, target, captured, 0)
                        .whenComplete((value, problem) -> {
                            if (problem == null) completed.complete(value);
                            else completed.completeExceptionally(problem);
                        });
            });
            return completed;
        });
        CompletableFuture<T> result = actual.thenCompose(Function.identity());
        ScarpetNativeWork.aliasDependency(result, actual);
        if (attacker instanceof ServerPlayer player) ScarpetPlayerInventoryGate.trackAccepted(player, result);
        result.whenComplete((ignored, failure) -> {
            if (failure != null)
                net.minecraft.server.MinecraftServer.LOGGER.error("Native attack continuation failed", failure);
        });
        return result;
    }

    private static <T> T withAcceptedPlayer(Entity attacker, java.util.function.Supplier<T> operation) {
        if (attacker instanceof ServerPlayer player) {
            try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                return operation.get();
            }
        }
        return operation.get();
    }

    /**
     * Typed native tails route every target endpoint separately, including when its world changed during the damage callback.
     */
    public static <T> CompletableFuture<T> afterDamageAsync(ServerPlayer attacker, CompletableFuture<Boolean> outcome,
                                                            Function<Boolean, CompletableFuture<T>> nativeTail) {
        var actual = afterDamageNativeAsync(attacker, outcome, nativeTail);
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    public static <T> CompletableFuture<T> afterDamageNativeAsync(ServerPlayer attacker, CompletableFuture<Boolean> outcome,
                                                                  Function<Boolean, CompletableFuture<T>> nativeTail) {
        TickThread.ensureTickThread(attacker, "Deferred typed attack must be captured by its attacker");
        var actual = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(attacker.carpetSpawnServer(), actual);
        var continuation = ScarpetRuntime.captureNativeFunction((Boolean hurt) -> ScarpetExplosionActors.entity(attacker, () -> {
            var observed = ScarpetNativeWork.observeNative(attacker, () -> {
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(attacker)) {
                    var result = nativeTail.apply(Boolean.TRUE.equals(hurt));
                    ScarpetNativeWork.record(result);
                    return result;
                }
            });
            ScarpetNativeWork.aliasDependency(actual, observed);
            return ScarpetNativeWork.recoverGuestValue(observed).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)));
        outcome.whenComplete(ScarpetRuntime.captureNativeConsumer((Boolean hurt, Throwable failure) -> {
            if (failure != null) {
                actual.completeExceptionally(failure);
                return;
            }
            continuation.apply(hurt).whenComplete((value, problem) -> {
                if (problem == null) actual.complete(value);
                else actual.completeExceptionally(problem);
            });
        }));
        ScarpetPlayerInventoryGate.trackAccepted(attacker, actual);
        return actual;
    }

    private static <T> CompletableFuture<T> jointlyOwned(Entity attacker, Entity target,
                                                         java.util.function.Supplier<T> tail, int attempt) {
        if (attempt == 8)
            return CompletableFuture.failedFuture(new IllegalStateException("Attack actors kept changing regions"));
        return ScarpetExplosionActors.entity(attacker, () -> {
            if (target == null || TickThread.isTickThreadFor(target) || ScarpetRetiredActors.knownRetired(target)
                    && TickThread.isTickThreadFor((ServerLevel) target.level(), target.blockPosition())) {
                return CompletableFuture.completedFuture(tail.get());
            }
            return ScarpetExplosionActors.entity(target, () -> new Location((ServerLevel) target.level(), target.blockPosition().immutable()))
                    .thenCompose(destination -> ScarpetExplosionActors.entity(attacker, () -> {
                        if (!(attacker.level() instanceof ServerLevel world))
                            throw new IllegalStateException("Deferred attack actor left the server world");
                        if (destination.world != world)
                            throw new IllegalStateException("Deferred attack actors moved to different dimensions");
                        BlockPos source = attacker.blockPosition();
                        int minX = Math.min(source.getX() >> 4, destination.position.getX() >> 4) - 8;
                        int minZ = Math.min(source.getZ() >> 4, destination.position.getZ() >> 4) - 8;
                        int maxX = Math.max(source.getX() >> 4, destination.position.getX() >> 4) + 8;
                        int maxZ = Math.max(source.getZ() >> 4, destination.position.getZ() >> 4) + 8;
                        return CarpetRegionLease.<CompletableFuture<T>>runValue(world, minX, minZ, maxX, maxZ, lease ->
                                ScarpetExplosionActors.entity(attacker, () -> {
                                    if (attacker.level() != world || !TickThread.isTickThreadFor(target)
                                            && !(ScarpetRetiredActors.knownRetired(target) && target.level() == world && TickThread.isTickThreadFor(world, target.blockPosition()))) {
                                        return jointlyOwned(attacker, target, tail, attempt + 1);
                                    }
                                    return CompletableFuture.completedFuture(tail.get());
                                }).thenCompose(Function.identity())).thenCompose(Function.identity());
                    }).thenCompose(Function.identity()));
        }).thenCompose(Function.identity());
    }
}
