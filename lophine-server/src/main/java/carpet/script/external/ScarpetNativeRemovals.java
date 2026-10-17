// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetScriptServer;
import carpet.script.EntityEventsGroup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Physical removal and every enclosing caller's cleanup follow the original pre-removal callback.
 */
public final class ScarpetNativeRemovals {
    public static final String EVENT_KEY = "scarpet:on_removed";
    private static final Map<Entity, Plan> PENDING = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final java.util.concurrent.ConcurrentHashMap<MinecraftServer, Set<CompletableFuture<?>>> CALLERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Set<Entity>> REPLAYING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private ScarpetNativeRemovals() {
    }

    private static final class Plan {
        final Entity entity;
        final MinecraftServer server;
        final Runnable physicalRemoval;
        ScarpetNativeWork.Token nativeToken;
        final ScarpetAttribution.Token attribution = ScarpetAttribution.capture();
        final CompletableFuture<Void> nativeBody = new CompletableFuture<>();
        final CompletableFuture<CompletableFuture<Void>> lifetimeReady = new CompletableFuture<>();
        volatile CompletableFuture<Void> completed = nativeBody;
        final List<Runnable> callerTails = new ArrayList<>();
        final AtomicBoolean queued = new AtomicBoolean();

        Plan(Entity entity, ServerLevel world, Runnable physicalRemoval) {
            this.entity = entity;
            this.server = world.getServer();
            this.physicalRemoval = physicalRemoval;
        }
    }

    public static boolean isPending(Entity entity) {
        return PENDING.containsKey(entity) || ScarpetNativeDeathEffects.isPending(entity);
    }

    public static boolean tickPending(Entity entity) {
        return isPending(entity) || entity instanceof net.minecraft.world.entity.LivingEntity living && ScarpetNativeDeaths.isPending(living) || entity instanceof net.minecraft.server.level.ServerPlayer player && fun.bm.lophine.carpet.CarpetPlayerBirths.playerPending(player);
    }

    /**
     * A dimension transform returns its real original object/copy after the removal head callback.
     */
    public static <T> CompletableFuture<T> removalValue(Entity entity, java.util.function.Supplier<T> nativePhysicalAndTail) {
        var result = new CompletableFuture<T>();
        var observed = ScarpetNativeWork.observeNative(entity, () -> {
            ScarpetNativeWork.record(result);
            Runnable physical = () -> {
                try {
                    result.complete(nativePhysicalAndTail.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    throw failure;
                }
            };
            if (!defer(entity, physical)) physical.run();
            return result;
        });
        var actual = observed.thenCompose(next -> next);
        ScarpetNativeWork.aliasDependency(actual, observed);
        return actual;
    }

    /**
     * Includes leave observers and inventory admission before the physical Entity.remove head.
     */
    public static <T> CompletableFuture<T> trackCaller(MinecraftServer server, CompletableFuture<T> actual) {
        Set<CompletableFuture<?>> callers = CALLERS.computeIfAbsent(server, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        callers.add(actual);
        actual.whenComplete((value, failure) -> {
            callers.remove(actual);
        });
        return actual;
    }

    public static CompletableFuture<Void> completion(Entity entity) {
        var prefix = ScarpetNativeDeathEffects.completion(entity);
        if (prefix != null) return prefix;
        Plan plan = PENDING.get(entity);
        if (plan == null || ScarpetRuntime.currentNativeDecision(entity, EVENT_KEY))
            return CompletableFuture.completedFuture(null);
        var view = plan.completed.copy();
        ScarpetNativeWork.aliasDependency(view, plan.completed);
        return view;
    }

    /**
     * Called precisely where the original Entity.remove HEAD callback ran, after subclass prefixes.
     */
    public static boolean defer(Entity entity, Runnable physicalRemoval) {
        if (REPLAYING.get().contains(entity)) return false;
        Plan existing = PENDING.get(entity);
        if (existing != null) {
            // A callback can itself physically remove its source. The original enclosing tails still follow it.
            return !ScarpetRuntime.currentNativeDecision(entity, EVENT_KEY);
        }
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel world)
                || !entity.carpetGetEventContainer().hasEvent(EntityEventsGroup.Event.ON_REMOVED) || !ScarpetRuntime.nativeEventsAllowed(world.getServer()))
            return false;
        ScarpetRetiredActors.capture(entity);
        var owner = ScarpetRetiredActors.lastOwner(entity);
        Plan plan = new Plan(entity, world, physicalRemoval);
        synchronized (PENDING) {
            if (PENDING.containsKey(entity)) return true;
            PENDING.put(entity, plan);
        }
        CompletableFuture<Void> actual = ScarpetNativeWork.observeNative(entity, () -> {
            plan.nativeToken = ScarpetNativeWork.capture();
            ScarpetNativeWork.record(plan.nativeBody);
            var event = ScarpetRuntime.captureOwnerOperation(() -> {
                if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                    try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                        return ScarpetRuntime.entityRemovalDecision(entity, () -> entity.carpetGetEventContainer().onEventFuture(EntityEventsGroup.Event.ON_REMOVED));
                    }
                }
                return ScarpetRuntime.entityRemovalDecision(entity, () -> entity.carpetGetEventContainer().onEventFuture(EntityEventsGroup.Event.ON_REMOVED));
            });
            // The lease follows the returned native tail. Waiting callbacks never relinquish their original region ticket.
            var lease = fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(world, owner.position().getX() >> 4,
                    owner.position().getZ() >> 4, owner.position().getX() >> 4, owner.position().getZ() >> 4, region -> {
                        onOwnerFuture(entity, event).thenCompose(next -> next)
                                .whenComplete((ignored, failure) -> queuePhysical(plan, failure));
                        return plan.lifetimeReady.thenCompose(next -> next);
                    }).thenCompose(next -> next);
            lease.whenComplete((ignored, failure) -> {
                if (failure != null && !plan.nativeBody.isDone()) queuePhysical(plan, failure);
            });
            return null;
        });
        plan.completed = actual;
        plan.lifetimeReady.complete(actual);
        if (entity instanceof net.minecraft.server.level.ServerPlayer player)
            ScarpetPlayerInventoryGate.trackAccepted(player, actual);
        actual.whenComplete((ignored, failure) -> {
            synchronized (PENDING) {
                PENDING.remove(entity, plan);
            }
        });
        return true;
    }

    /**
     * Invoked immediately after super.remove or removePlayerImmediately, preserving inner-to-outer caller order.
     */
    public static boolean thenOwner(Entity entity, Runnable callerTail) {
        if (ScarpetNativeDeathEffects.thenOwner(entity, callerTail)) return true;
        Plan plan = PENDING.get(entity);
        if (plan == null || REPLAYING.get().contains(entity) || ScarpetRuntime.currentNativeDecision(entity, EVENT_KEY))
            return false;
        synchronized (plan.callerTails) {
            plan.callerTails.add(callerTail);
        }
        return true;
    }

    public static <T> CompletableFuture<T> onOwnerFuture(Entity entity, java.util.function.Supplier<T> operation) {
        if (ca.spottedleaf.moonrise.common.util.TickThread.isShutdownThread()
                || ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        dispatchCurrentOwner(entity, operation, result);
        return result;
    }

    private static <T> void dispatchCurrentOwner(Entity entity, java.util.function.Supplier<T> operation, CompletableFuture<T> result) {
        var location = ScarpetRetiredActors.lastOwner(entity);
        if (location == null) {
            result.completeExceptionally(new IllegalStateException("Native removal lost its captured owner"));
            return;
        }
        try {
            location.world().getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, location.world().getWorld(), location.position().getX() >> 4,
                    location.position().getZ() >> 4, () -> {
                        if (!ScarpetRetiredActors.matchesLastOwner(entity, location)) {
                            dispatchCurrentOwner(entity, operation, result);
                            return;
                        }
                        try {
                            result.complete(operation.get());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static void queuePhysical(Plan plan, Throwable guestFailure) {
        if (!plan.queued.compareAndSet(false, true)) return;
        if (guestFailure != null)
            CarpetScriptServer.LOG.error("Scarpet removal callback failed; completing original Native removal", unwrap(guestFailure));
        dispatchLastOwner(plan);
    }

    private static void dispatchLastOwner(Plan plan) {
        if (plan.completed.isDone()) return;
        var location = ScarpetRetiredActors.lastOwner(plan.entity);
        if (location == null) {
            finish(plan, new IllegalStateException("Native removal lost its captured owner"));
            return;
        }
        // This is mandatory Native cleanup, so Runtime's guest-closing admission must not reject it.
        try {
            location.world().getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, location.world().getWorld(),
                    location.position().getX() >> 4, location.position().getZ() >> 4, () -> {
                        if (!ScarpetRetiredActors.matchesLastOwner(plan.entity, location)) {
                            dispatchLastOwner(plan);
                            return;
                        }
                        Set<Entity> replaying = REPLAYING.get();
                        boolean added = replaying.add(plan.entity);
                        try {
                            Runnable actual = () -> ScarpetNativeWork.with(plan.nativeToken, () -> ScarpetAttribution.with(plan.attribution, () -> {
                                plan.physicalRemoval.run();
                                List<Runnable> tails;
                                synchronized (plan.callerTails) {
                                    tails = List.copyOf(plan.callerTails);
                                }
                                for (Runnable tail : tails) tail.run();
                            }));
                            if (plan.entity instanceof net.minecraft.server.level.ServerPlayer player) {
                                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                                    actual.run();
                                }
                            } else actual.run();
                            finish(plan, null);
                        } catch (Throwable failure) {
                            finish(plan, failure);
                        } finally {
                            if (added) replaying.remove(plan.entity);
                            ScarpetRetiredActors.capture(plan.entity);
                        }
                    });
        } catch (Throwable failure) {
            finish(plan, failure);
        }
    }

    private static void finish(Plan plan, Throwable failure) {
        if (failure == null) plan.nativeBody.complete(null);
        else plan.nativeBody.completeExceptionally(failure);
    }

    /**
     * Call after guest shutdown admission closes, before halting region schedulers.
     */
    public static CompletableFuture<Void> whenIdle(MinecraftServer server) {
        CompletableFuture<?>[] pending;
        synchronized (PENDING) {
            var callers = CALLERS.getOrDefault(server, Set.of());
            pending = java.util.stream.Stream.concat(callers.stream(), PENDING.values().stream()
                            .filter(plan -> plan.server == server && !plan.completed.isDone()).map(plan -> plan.completed))
                    .filter(future -> !future.isDone()).distinct().toArray(CompletableFuture[]::new);
        }
        return CompletableFuture.allOf(pending).handle((ignored, failure) -> null).thenCompose(ignored -> {
            synchronized (PENDING) {
                if (PENDING.values().stream().noneMatch(plan -> plan.server == server && !plan.completed.isDone())
                        && CALLERS.getOrDefault(server, Set.of()).stream().allMatch(CompletableFuture::isDone))
                    return CompletableFuture.completedFuture(null);
            }
            return whenIdle(server);
        });
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }
}
