// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer;
import carpet.script.CarpetScriptServer;
import carpet.script.EntityEventsGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Suspends the actual source death HEAD and keeps every native enclosing death tail in its original order.
 */
public final class ScarpetNativeDeaths {
    public static final Object EVENT_KEY = new Object();
    private static final WeakIdentityMap<LivingEntity, Plan> PENDING = new WeakIdentityMap<>();
    private static final ThreadLocal<Plan> ACTIVE_PLAN = new ThreadLocal<>();
    private static final ThreadLocal<Set<LivingEntity>> REPLAYING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final class Plan {
        final LivingEntity target;
        final MinecraftServer server;
        final List<Runnable> tails = new ArrayList<>();
        final CompletableFuture<Void> result = new CompletableFuture<>();
        volatile boolean physicalFinished;

        Plan(LivingEntity target) {
            this.target = target;
            this.server = target.level().getServer();
        }
    }

    private ScarpetNativeDeaths() {
    }

    /**
     * Pinned fake-player prefix: dismount a player vehicle, then all indirect player passengers.
     */
    public static CompletableFuture<Void> shakeOff(ServerPlayer player) {
        Supplier<CompletableFuture<Void>> passengers = ScarpetRuntime.captureNativeContinuation(() ->
                passengers(player).thenCompose(all -> ScarpetNativeDeathEffects.sequence(player, all.iterator(), passenger -> {
                    if (passenger instanceof net.minecraft.world.entity.player.Player)
                        ScarpetNativeWork.record(stopRiding(passenger));
                }, () -> {
                })));
        return ScarpetExplosionActors.entity(player, () -> player.getVehicle() instanceof net.minecraft.world.entity.player.Player)
                .thenCompose(playerVehicle -> playerVehicle ? stopRiding(player) : CompletableFuture.completedFuture(null)).thenCompose(ignored -> passengers.get());
    }

    private static CompletableFuture<List<net.minecraft.world.entity.Entity>> passengers(net.minecraft.world.entity.Entity parent) {
        return ScarpetExplosionActors.entity(parent, () -> List.copyOf(parent.getPassengers())).thenCompose(direct -> {
            CompletableFuture<List<net.minecraft.world.entity.Entity>> result = CompletableFuture.completedFuture(new ArrayList<>());
            for (var child : direct) {
                var descendants = ScarpetRuntime.captureNativeContinuation(() -> passengers(child));
                result = result.thenCompose(all -> descendants.get().thenApply(nested -> {
                    all.add(child);
                    all.addAll(nested);
                    return all;
                }));
            }
            return result;
        });
    }

    private static CompletableFuture<Void> stopRiding(net.minecraft.world.entity.Entity rider) {
        return ScarpetNativeRelationships.stopRiding(rider, false);
    }

    public static boolean isPending(LivingEntity target) {
        Plan plan = PENDING.get(target);
        return plan != null && !plan.result.isDone();
    }

    public static CompletableFuture<Void> completion(LivingEntity target) {
        Plan plan = PENDING.get(target);
        if (plan == null) return null;
        var caller = plan.result.copy();
        ScarpetNativeWork.aliasDependency(caller, plan.result);
        return caller;
    }

    public static boolean thenOwner(LivingEntity target, Runnable nativeTail) {
        Plan active = ACTIVE_PLAN.get();
        Plan plan = active != null && active.target == target ? active : PENDING.get(target);
        if (plan != null && !plan.physicalFinished) synchronized (plan.tails) {
            if (!plan.physicalFinished) {
                plan.tails.add(nativeTail);
                return true;
            }
        }
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(target)) {
            var actual = ScarpetExplosionActors.entity(target, () -> {
                nativeTail.run();
                return null;
            });
            var owner = ScarpetRetiredActors.lastOwner(target);
            if (owner == null) throw new IllegalStateException("Native death lost its captured owner");
            ScarpetNativeWork.trackNative(owner.world().getServer(), actual);
            return true;
        }
        return false;
    }

    /**
     * The real death body and every overridden enclosing tail precede the original post-damage boolean body.
     */
    public static CompletableFuture<Boolean> afterDeath(LivingEntity target, Runnable death, java.util.function.BooleanSupplier nativeTail) {
        var observed = ScarpetNativeWork.observeNative(target, () -> {
            Supplier<CompletableFuture<Boolean>> continuation = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, nativeTail::getAsBoolean));
            var dying = ScarpetNativeWork.observeNative(target, () -> {
                death.run();
                return null;
            });
            CompletableFuture<Boolean> actual = ScarpetNativeWork.recoverGuestValue(dying).thenCompose(ignored -> continuation.get());
            ScarpetNativeWork.record(actual);
            return actual;
        });
        CompletableFuture<Boolean> actual = ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value);
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetNativeWork.record(actual);
        return actual;
    }

    /**
     * Called exactly where pinned LivingEntity or ServerPlayer receives its injected HEAD event.
     */
    public static boolean defer(LivingEntity target, DamageSource source, Runnable nativeBody) {
        if (REPLAYING.get().contains(target)) return false;
        if (ScarpetRuntime.currentNativeDecision(target, EVENT_KEY))
            throw new carpet.script.exception.InternalExpressionException("A death callback cannot synchronously await its own death");
        Plan previous = PENDING.get(target);
        EntityEventsGroup group = target.carpetPeekEventContainer();
        boolean observed = group != null && group.hasEvent(EntityEventsGroup.Event.ON_DEATH);
        boolean playerEvent = target instanceof ServerPlayer && CarpetEventServer.Event.PLAYER_DIES.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get();
        if (!ScarpetRuntime.nativeEventsAllowed(target.level().getServer())) {
            observed = false;
            playerEvent = false;
        }
        final boolean observeEntity = observed, observePlayer = playerEvent;
        Plan plan = new Plan(target);
        PENDING.put(target, plan);
        // The private receipt is visible before queueing; callers receive cancellable copies only.
        ScarpetNativeWork.record(plan.result);
        ScarpetNativeWork.trackNative(plan.server, plan.result);
        var begin = previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.result.handle((ignored, failure) -> null);
        Supplier<CompletableFuture<CompletableFuture<Void>>> start = ScarpetRuntime.captureNativeContinuation(() -> ScarpetNativeWork.observeNative(target, () -> {
            Supplier<CompletableFuture<Void>> nativeContinuation = ScarpetRuntime.captureNativeContinuation(() -> {
                Supplier<CompletableFuture<Void>> enclosing = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, () -> {
                    List<Runnable> tails;
                    synchronized (plan.tails) {
                        tails = List.copyOf(plan.tails);
                        plan.physicalFinished = true;
                    }
                    return ScarpetNativeDeathEffects.sequence(target, tails.iterator(), tail -> ScarpetNativeWork.record(physicalBody(target, () -> withPlan(plan, tail))), () -> {
                    });
                }).thenCompose(value -> value));
                return physicalBody(target, () -> withPlan(plan, nativeBody)).thenCompose(ignored -> enclosing.get());
            });
            Supplier<CompletableFuture<Void>> event = ScarpetRuntime.captureNativeContinuation(() -> ScarpetRuntime.nativeDeathDecision(target, () -> {
                Supplier<CompletableFuture<Void>> playerCallback = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, () ->
                        ScarpetRuntime.captureEvent(() -> CarpetEventServer.Event.PLAYER_DIES.onPlayerEvent((ServerPlayer) target)).thenApply(unused -> (Void) null)).thenCompose(value -> value));
                CompletableFuture<Void> callbacks = observeEntity ? group.onEventFuture(EntityEventsGroup.Event.ON_DEATH, source.getMsgId()) : CompletableFuture.completedFuture(null);
                return callbacks.handle((ignored, failure) -> {
                    if (failure != null)
                        CarpetScriptServer.LOG.error("Scarpet entity death callback failed; completing the original player event sequence", failure);
                    return (Void) null;
                }).thenCompose(ignored -> {
                    if (!observePlayer) return CompletableFuture.completedFuture(null);
                    return playerCallback.get();
                });
            }));
            CompletableFuture<Void> physical = ScarpetExplosionActors.entity(target, event).thenCompose(value -> value)
                    .handle((ignored, failure) -> failure).thenCompose(guestFailure -> {
                        if (guestFailure != null)
                            CarpetScriptServer.LOG.error("Scarpet death callback failed; completing the accepted native death", guestFailure);
                        return nativeContinuation.get();
                    });
            ScarpetNativeWork.record(physical);
            return physical;
        }));
        var actual = begin.thenCompose(ignored -> {
            var observedScope = start.get();
            ScarpetNativeWork.aliasDependency(plan.result, observedScope);
            var body = ScarpetNativeWork.recoverGuestValue(observedScope).thenCompose(value -> value);
            ScarpetNativeWork.aliasDependency(body, observedScope);
            return body;
        });
        if (previous != null) ScarpetNativeWork.linkDependency(plan.result, previous.result);
        actual.whenComplete((ignored, failure) -> {
            PENDING.remove(target, plan);
            if (failure == null) plan.result.complete(null);
            else plan.result.completeExceptionally(failure);
        });
        return true;
    }

    private static void withPlan(Plan plan, Runnable operation) {
        Plan previous = ACTIVE_PLAN.get();
        ACTIVE_PLAN.set(plan);
        try {
            operation.run();
        } finally {
            if (previous == null) ACTIVE_PLAN.remove();
            else ACTIVE_PLAN.set(previous);
        }
    }

    /**
     * Vanilla death drops, shoulders, beds and neutral-mob forgiveness share the actual 32-block owner footprint.
     */
    private static CompletableFuture<Void> physicalBody(LivingEntity target, Runnable body) {
        return ScarpetExplosionActors.entity(target, () -> {
            ServerLevel world = (ServerLevel) target.level();
            BlockPos position = target.blockPosition().immutable();
            int minX = (position.getX() - 32) >> 4, maxX = (position.getX() + 32) >> 4, minZ = (position.getZ() - 32) >> 4, maxZ = (position.getZ() + 32) >> 4;
            Supplier<CompletableFuture<Void>> perform = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, () -> {
                BlockPos now = target.blockPosition();
                if (target.level() != world || ((now.getX() - 32) >> 4) < minX || ((now.getX() + 32) >> 4) > maxX
                        || ((now.getZ() - 32) >> 4) < minZ || ((now.getZ() + 32) >> 4) > maxZ)
                    return physicalBody(target, body);
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(target, () -> {
                    Set<LivingEntity> replaying = REPLAYING.get();
                    boolean added = replaying.add(target);
                    try {
                        body.run();
                        return (Void) null;
                    } finally {
                        if (added) replaying.remove(target);
                    }
                }));
            }).thenCompose(value -> value));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runOwnedPhaseValue(world, minX, minZ, maxX, maxZ, lease -> perform.get()).thenCompose(value -> value);
        }).thenCompose(value -> value);
    }
}
