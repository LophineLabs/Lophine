// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer.Event;
import carpet.script.EntityEventsGroup;
import carpet.script.value.EntityValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Suspends the true pre-armor damage phase and continues its actual native outer body. */
public final class ScarpetDamageContinuations {
    private record Decision(Boolean cancelled, Throwable failure) {}
    private static final ThreadLocal<LivingEntity> INNER_RESUME = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> PAUSED_ADMISSION = new ThreadLocal<>();
    public static boolean isInnerResume(LivingEntity target) { return INNER_RESUME.get() == target; }
    public static boolean resumeInner(LivingEntity target, java.util.function.BooleanSupplier operation) {
        LivingEntity previous = INNER_RESUME.get(); INNER_RESUME.set(target);
        try { return operation.getAsBoolean(); }
        finally { if (previous == null) INNER_RESUME.remove(); else INNER_RESUME.set(previous); }
    }
    private static final WeakIdentityMap<LivingEntity, Reservation> PENDING = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LivingEntity, CompletableFuture<Void>> SERIAL = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LivingEntity, CompletableFuture<Boolean>> OUTCOMES = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LivingEntity, CompletableFuture<Boolean>> EXTENDED_RESULTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<LivingEntity, CompletableFuture<Void>> PAUSED_SERIAL = new WeakIdentityMap<>();
    private static final class Reservation {
        final ServerLevel world; volatile long expiresAt;
        volatile float lastHurt;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Reservation(ServerLevel world, float lastHurt, int duration) { this.world = world; this.expiresAt = world.getRedstoneGameTime() + duration; this.lastHurt = lastHurt; }
    }
    public static float effectiveLastHurt(LivingEntity target, ServerLevel world, float actual) {
        Reservation pending = PENDING.get(target);
        return pending == null || pending.world != world || ScarpetRuntime.isDamageCallbackFor(target) ? actual : Math.max(actual, pending.lastHurt);
    }
    public static float effectiveCooldown(LivingEntity target, ServerLevel world, float actual) {
        Reservation pending = PENDING.get(target);
        if (pending == null || pending.world != world || ScarpetRuntime.isDamageCallbackFor(target)) return actual;
        return Math.max(actual, Math.max(0L, pending.expiresAt - world.getRedstoneGameTime()));
    }
    private static Reservation reserve(LivingEntity target, ServerLevel world, float rawLastHurt, int duration, boolean full) {
        return PENDING.compute(target, (key, previous) -> {
            Reservation pending = previous == null || previous.world != world ? new Reservation(world, rawLastHurt, duration) : previous;
            pending.lastHurt = full ? rawLastHurt : Math.max(pending.lastHurt, rawLastHurt);
            pending.expiresAt = Math.max(pending.expiresAt, world.getRedstoneGameTime() + duration);
            pending.count.incrementAndGet(); return pending;
        });
    }
    private static void release(LivingEntity target, Reservation pending) { if (pending.count.decrementAndGet() == 0) PENDING.remove(target, pending); }
    public static CompletableFuture<Void> pendingCompletion(net.minecraft.world.entity.Entity target) { return target instanceof LivingEntity living ? SERIAL.get(living) : null; }
    public static CompletableFuture<Boolean> pendingResult(net.minecraft.world.entity.Entity target) {
        if (target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
            var actual = ScarpetRenewableDragonHead.pendingResult(dragon); if (actual != null) return actual;
        }
        if(!(target instanceof LivingEntity living))return null;
        CompletableFuture<Boolean> extended=EXTENDED_RESULTS.get(living);if(extended!=null)return extended;
        CompletableFuture<Boolean> outer=OUTCOMES.get(living);return outer==null?BODY_RESULTS.get(living):outer;
    }
    /** Extends a deferred damage result through entity overrides that have vanilla post-damage work. */
    public static void publishExtendedResult(LivingEntity target, CompletableFuture<Boolean> actual) {
        EXTENDED_RESULTS.put(target, actual);
        CompletableFuture<Void> serial=actual.thenApply(ignored->(Void)null);
        SERIAL.put(target,serial);
        ScarpetNativeWork.record(actual);ScarpetNativeWork.record(serial);
        if(target.level() instanceof ServerLevel world)ScarpetNativeWork.trackNative(world.getServer(),serial);
        actual.whenComplete((ignored,failure)->EXTENDED_RESULTS.remove(target,actual));
        serial.whenComplete((ignored,failure)->SERIAL.remove(target,serial));
    }
    /** Runs a living-entity override tail on the victim owner and chains it into the exposed damage receipt. */
    public static CompletableFuture<Boolean> appendNativeResult(LivingEntity target, CompletableFuture<Boolean> outcome,
                                                                 java.util.function.Function<Boolean, Boolean> nativeTail) {
        var captured = ScarpetRuntime.captureNativeFunction((Boolean hurt) -> ScarpetExplosionActors.entity(target,
            () -> nativeTail.apply(Boolean.TRUE.equals(hurt))));
        CompletableFuture<Boolean> actual = outcome.thenCompose(captured);
        publishExtendedResult(target, actual);
        return actual;
    }
    private static final WeakIdentityMap<LivingEntity,CompletableFuture<Boolean>> BODY_RESULTS=new WeakIdentityMap<>();
    public static CompletableFuture<Boolean> pendingBodyResult(net.minecraft.world.entity.Entity target) { return target instanceof LivingEntity living?BODY_RESULTS.get(living):null; }
    public static void publishBodyResult(LivingEntity target,CompletableFuture<Boolean> actual) {
        BODY_RESULTS.put(target,actual);ScarpetNativeWork.record(actual);
        actual.whenComplete((ignored,failure)->BODY_RESULTS.remove(target,actual));
    }
    /** Queues the whole Native mutable prefix while a player inventory snapshot owns the admission gate. */
    public static boolean deferPaused(LivingEntity target, java.util.function.Supplier<Boolean> entireNativeDamage) {
        if (!(target instanceof ServerPlayer player) || !ScarpetPlayerInventoryGate.paused(player)) return false;
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        CompletableFuture<Void> tail = new CompletableFuture<>();
        OUTCOMES.put(target, outcome); SERIAL.put(target, tail);
        var captured = ScarpetRuntime.captureOwnerOperation(() -> observePausedDamage(target, entireNativeDamage));
        ScarpetNativeWork.record(tail);
        PAUSED_SERIAL.compute(target, (key, previous) -> {
            CompletableFuture<Void> begin = previous == null ? CompletableFuture.completedFuture(null) : previous.handle((ignored, failure) -> null);
            begin.thenCompose(ignored -> admitPaused(target, player, captured)).whenComplete((value, failure) -> {
                SERIAL.remove(target, tail); OUTCOMES.remove(target, outcome); PAUSED_SERIAL.remove(target, tail);
                if (failure == null) { outcome.complete(value); tail.complete(null); }
                else { outcome.completeExceptionally(failure); tail.completeExceptionally(failure); }
            });
            return tail;
        });
        return true;
    }
    private static CompletableFuture<Boolean> admitPaused(LivingEntity target, ServerPlayer player, java.util.function.Supplier<CompletableFuture<Boolean>> entireNativeDamage) {
        return ScarpetPlayerInventoryGate.whenOpen(player).thenCompose(ignored -> ScarpetRuntime.<CompletableFuture<Boolean>>atEntityFuture(target, () -> {
            if (ScarpetPlayerInventoryGate.paused(player)) return admitPaused(target, player, entireNativeDamage);
            CompletableFuture<Boolean> admitted = new CompletableFuture<>();
            ScarpetPlayerInventoryGate.trackAccepted(player, admitted);
            LivingEntity previous = PAUSED_ADMISSION.get(); PAUSED_ADMISSION.set(target);
            try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                entireNativeDamage.get().whenComplete((result, failure) -> {
                    if (failure == null) admitted.complete(result); else admitted.completeExceptionally(failure);
                });
            } catch (Throwable failure) { admitted.completeExceptionally(failure); }
            finally { if (previous == null) PAUSED_ADMISSION.remove(); else PAUSED_ADMISSION.set(previous); }
            return admitted;
        })).thenCompose(next -> next);
    }
    /** Captures the true Boolean of a whole owner phase which may publish another native damage result. */
    public static CompletableFuture<Boolean> observeNativeBody(LivingEntity target, java.util.function.Supplier<Boolean> body) {
        return observePausedDamage(target, body);
    }
    private static CompletableFuture<Boolean> observePausedDamage(LivingEntity target, java.util.function.Supplier<Boolean> entireNativeDamage) {
        CompletableFuture<Boolean> before = pendingResult(target),beforeBody=pendingBodyResult(target);
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>> inner = new java.util.concurrent.atomic.AtomicReference<>();
        var actual = ScarpetNativeWork.observeNative(target, () -> {
            boolean provisional = entireNativeDamage.get();
            CompletableFuture<Boolean> after = pendingResult(target);
            if (after != null && after != before) inner.set(after);
            else {var afterBody=pendingBodyResult(target);if(afterBody!=null&&afterBody!=beforeBody)inner.set(afterBody);}
            return provisional;
        });
        return ScarpetNativeWork.recoverGuestValue(actual).thenCompose(provisional -> inner.get() == null ? CompletableFuture.completedFuture(provisional) : inner.get());
    }
    private ScarpetDamageContinuations() {}
    public static boolean defer(LivingEntity target, ServerLevel world, DamageSource source, float amount, float rawLastHurt, int remainingCooldown, boolean full, Function<Boolean, Boolean> nativeRemainder) {
        if (target instanceof ServerPlayer player) {
            return ScarpetPlayerInventoryGate.observeAccepted(player,
                () -> deferAccepted(target, world, source, amount, rawLastHurt, remainingCooldown, full, nativeRemainder));
        }
        return deferAccepted(target, world, source, amount, rawLastHurt, remainingCooldown, full, nativeRemainder);
    }

    private static boolean deferAccepted(LivingEntity target, ServerLevel world, DamageSource source, float amount, float rawLastHurt, int remainingCooldown, boolean full, Function<Boolean, Boolean> nativeRemainder) {
        EntityEventsGroup group = target.carpetPeekEventContainer();
        boolean observe = group != null && group.hasEvent(EntityEventsGroup.Event.ON_DAMAGE);
        boolean taking = target instanceof ServerPlayer && Event.PLAYER_TAKES_DAMAGE.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get();
        boolean dealing = source.getEntity() instanceof ServerPlayer && Event.PLAYER_DEALS_DAMAGE.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get();
        if (!observe && !taking && !dealing) return false;
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        OUTCOMES.put(target, outcome);
        java.util.concurrent.atomic.AtomicBoolean actualResult = new java.util.concurrent.atomic.AtomicBoolean(false);
        Reservation reservation = reserve(target, world, rawLastHurt, remainingCooldown, full);
        var token = ScarpetNativeWork.capture();
        var attribution = ScarpetAttribution.capture();
        var prerequisite = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
        CompletableFuture<Void> tail = SERIAL.compute(target, (key, previous) -> {
            if (previous != null && !ScarpetRuntime.isDamageCallbackFor(target)) prerequisite.set(previous);
            CompletableFuture<Void> begin = previous == null || ScarpetRuntime.isDamageCallbackFor(target) || PAUSED_ADMISSION.get() == target
                ? CompletableFuture.completedFuture(null) : previous.handle((ignored, failure) -> null);
            var replayCancelled=new java.util.concurrent.atomic.AtomicBoolean();
            var replay=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(target, () -> {
                        
                        var beforeBody=pendingBodyResult(target);
                        var afterBody=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
                        var observedBody=ScarpetNativeWork.observeNative(target, () -> ScarpetAttribution.with(attribution, () -> {
                            if (target instanceof ServerPlayer player) {
                                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                                    boolean immediate=player.carpetContinueQueuedHealthUpdate(() -> nativeRemainder.apply(replayCancelled.get()));
                                    afterBody.set(pendingBodyResult(target));return immediate;
                                }
                            }
                            boolean immediate=nativeRemainder.apply(replayCancelled.get());afterBody.set(pendingBodyResult(target));return immediate;
                        }));
                        return ScarpetNativeWork.recoverGuestValue(observedBody).thenCompose(immediate->afterBody.get()!=null&&afterBody.get()!=beforeBody?afterBody.get():CompletableFuture.completedFuture(immediate));
                    }).thenCompose(value->value));
            var finalizeHealth=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(target,()->{
                if(target instanceof ServerPlayer player)player.carpetCompleteDamageHealth(outcome);
                return null;
            }));
            CompletableFuture<Void> body=begin.thenCompose(ignored -> ScarpetExplosionActors.entity(target, () -> {
                CompletableFuture<Void> observed = observe ? group.onEventFuture(EntityEventsGroup.Event.ON_DAMAGE, amount, source) : CompletableFuture.completedFuture(null);
                CompletableFuture<Boolean> decision = observed.thenCompose(done -> taking
                    ? ScarpetRuntime.ownerEventDecision(Event.PLAYER_TAKES_DAMAGE.handler,
                        List.of(new EntityValue(target), NumericValue.of(amount), StringValue.of(source.getMsgId()), EntityValue.of(source.getEntity())), target, target)
                    : CompletableFuture.completedFuture(false));
                if (dealing) decision = decision.thenCompose(cancelled -> ScarpetRuntime.ownerEventDecision(Event.PLAYER_DEALS_DAMAGE.handler,
                    List.of(new EntityValue(source.getEntity()), NumericValue.of(amount), new EntityValue(target)), source.getEntity(), target).thenApply(second -> cancelled || second));
                return decision;
            }).thenCompose(decision -> decision)).handle((cancelled, failure) -> new Decision(cancelled, failure))
                .thenCompose(decision -> {
                    if (decision.cancelled() == null && decision.failure() == null) return CompletableFuture.completedFuture(null);
                    if (decision.failure() != null) carpet.script.CarpetScriptServer.LOG.error("Scarpet damage phase failed", decision.failure());
                    replayCancelled.set(Boolean.TRUE.equals(decision.cancelled()));
                    return replay.get().thenAccept(actualResult::set);
                });

            return body.handle((ignored,failure)->failure).thenCompose(failure->finalizeHealth.get().thenApply(unused->{
                if(failure!=null)throw new java.util.concurrent.CompletionException(failure);return null;
            }));
        });
        ScarpetNativeWork.record(tail);ScarpetNativeWork.trackNative(world.getServer(),tail);
        if (prerequisite.get() != null) ScarpetNativeWork.linkDependency(tail, prerequisite.get());
        if (target instanceof ServerPlayer player) ScarpetPlayerInventoryGate.trackAccepted(player, tail);
        tail.whenComplete((ignored, failure) -> {
            release(target, reservation); SERIAL.remove(target, tail);
            OUTCOMES.remove(target, outcome);
            if (failure == null) outcome.complete(actualResult.get()); else outcome.completeExceptionally(failure);
            if (failure != null) carpet.script.CarpetScriptServer.LOG.error("Scarpet damage continuation failed", failure);
        });
        return true;
    }
}
