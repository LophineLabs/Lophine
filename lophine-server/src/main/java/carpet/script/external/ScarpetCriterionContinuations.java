// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.EntityHurtPlayerTrigger;
import net.minecraft.advancements.triggers.KilledTrigger;
import net.minecraft.advancements.triggers.PlayerHurtEntityTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Listener matching is complete before the original ordered awards, all performed by the real player owner.
 */
public final class ScarpetCriterionContinuations {
    private ScarpetCriterionContinuations() {
    }

    private record Listener<T>(PlayerAdvancements.TriggerInstanceKey key, T value) {
    }

    private record Snapshot<T>(PlayerAdvancements advancements, List<Listener<T>> listeners) {
    }

    private static LootContext context(ServerPlayer player, Entity actual) {
        LootContext context = EntityPredicate.createContext(player, actual);
        ScarpetLootRandomOwners.bind(context, new ScarpetAttackEnchantments.SourceAdmission(context.getLevel(), player.blockPosition(), context.getRandom(), player));
        return context;
    }

    public static <T extends SimpleCriterionTrigger.SimpleInstance> CompletableFuture<Void> trigger(
            SimpleCriterionTrigger<T> trigger, ServerPlayer player, Function<T, CompletableFuture<Boolean>> matcher) {
        boolean nativeCaller = ScarpetNativeWork.capture() != null;
        var actual = triggerNative(trigger, player, matcher);
        if (nativeCaller) return actual;
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    public static <T extends SimpleCriterionTrigger.SimpleInstance> CompletableFuture<Void> triggerNative(
            SimpleCriterionTrigger<T> trigger, ServerPlayer player, Function<T, CompletableFuture<Boolean>> matcher) {
        return jobNative(player, () -> ScarpetLootConditions.actor(player, () -> {
            if (player instanceof org.leavesmc.leaves.bot.ServerBot) return new Snapshot<T>(null, List.of());
            PlayerAdvancements advancements = player.getAdvancements();
            var listeners = advancements.getTriggerMapForType(trigger);
            List<Listener<T>> snapshot = new ArrayList<>();
            if (listeners != null)
                for (var entry : listeners.entrySet()) snapshot.add(new Listener<>(entry.getKey(), entry.getValue()));
            return new Snapshot<T>(advancements, List.copyOf(snapshot));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(snapshot -> match(player, matcher, snapshot, 0, new ArrayList<>(), new LootContext[1]))));
    }

    private static <T extends SimpleCriterionTrigger.SimpleInstance> CompletableFuture<Void> match(ServerPlayer player,
                                                                                                   Function<T, CompletableFuture<Boolean>> matcher, Snapshot<T> snapshot, int index, List<PlayerAdvancements.TriggerInstanceKey> matched, LootContext[] playerContext) {
        if (index == snapshot.listeners().size()) return award(player, snapshot.advancements(), matched, 0);
        Listener<T> listener = snapshot.listeners().get(index);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> match(player, matcher, snapshot, index + 1, matched, playerContext));
        var observed = ScarpetNativeWork.observeNative(player, () -> {
            var result = matcher.apply(listener.value());
            ScarpetNativeWork.record(result);
            return result;
        });
        return ScarpetNativeWork.recoverGuestValue(observed).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value))
                .thenCompose(ScarpetRuntime.captureNativeFunction(passed -> {
                    if (!passed) return next.get();
                    var predicate = listener.value().player();
                    CompletableFuture<Boolean> allowed;
                    if (predicate.isEmpty()) allowed = CompletableFuture.completedFuture(true);
                    else allowed = ScarpetLootConditions.actor(player, () -> {
                        if (playerContext[0] == null) playerContext[0] = context(player, player);
                        return playerContext[0];
                    }).thenCompose(ScarpetRuntime.captureNativeFunction(context -> ScarpetLootConditions.test(predicate.get().value(), context)));
                    return allowed.thenCompose(ScarpetRuntime.captureNativeFunction(value -> {
                        if (value) matched.add(listener.key());
                        return next.get();
                    }));
                }));
    }

    private static CompletableFuture<Void> award(ServerPlayer player, PlayerAdvancements advancements, List<PlayerAdvancements.TriggerInstanceKey> matched, int index) {
        if (index == matched.size()) return CompletableFuture.completedFuture(null);
        var criterion = matched.get(index);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> award(player, advancements, matched, index + 1));
        return ScarpetExplosionActors.admitTarget(player, () -> ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(player, () -> {
            advancements.award(criterion.advancement(), criterion.criterion());
            return (Void) null;
        }))).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }

    public static CompletableFuture<Void> killed(KilledTrigger trigger, ServerPlayer player, Entity entity, DamageSource killingBlow) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, entity))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> triggerNative(trigger, player, value -> {
                    var damage = value.killingBlow().isEmpty() ? CompletableFuture.completedFuture(true)
                            : ScarpetLootConditions.damage(value.killingBlow().get(), player, killingBlow);
                    Supplier<CompletableFuture<Boolean>> entityCondition = ScarpetRuntime.captureNativeContinuation(() -> value.entity().isEmpty()
                            ? CompletableFuture.completedFuture(true) : ScarpetLootConditions.test(value.entity().get().value(), context));
                    return damage.thenCompose(ScarpetRuntime.captureNativeFunction(passed -> passed ? entityCondition.get() : CompletableFuture.completedFuture(false)));
                }))));
    }

    public static CompletableFuture<Void> playerHurt(PlayerHurtEntityTrigger trigger, ServerPlayer player, Entity victim, DamageSource source, float original, float actual, boolean blocked) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, victim))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> triggerNative(trigger, player, value -> {
                    var damage = value.damage().isEmpty() ? CompletableFuture.completedFuture(true)
                            : ScarpetLootConditions.damage(value.damage().get(), player, source, original, actual, blocked);
                    Supplier<CompletableFuture<Boolean>> entityCondition = ScarpetRuntime.captureNativeContinuation(() -> value.entity().isEmpty()
                            ? CompletableFuture.completedFuture(true) : ScarpetLootConditions.test(value.entity().get().value(), context));
                    return damage.thenCompose(ScarpetRuntime.captureNativeFunction(passed -> passed ? entityCondition.get() : CompletableFuture.completedFuture(false)));
                }))));
    }

    public static CompletableFuture<Void> playerHurtBy(EntityHurtPlayerTrigger trigger, ServerPlayer player, DamageSource source, float original, float actual, boolean blocked) {
        return trigger(trigger, player, value -> value.damage().isEmpty() ? CompletableFuture.completedFuture(true)
                : ScarpetLootConditions.damage(value.damage().get(), player, source, original, actual, blocked));
    }

    private static <T> CompletableFuture<T> job(ServerPlayer player, Supplier<CompletableFuture<T>> body) {
        boolean nativeCaller = ScarpetNativeWork.capture() != null;
        var actual = jobNative(player, body);
        if (nativeCaller) return actual;
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    private static <T> CompletableFuture<T> jobNative(ServerPlayer player, Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        try {
            ScarpetNativeWork.trackNative(player.carpetSpawnServer(), actual);
            var observed = ScarpetNativeWork.observeNative(player, () -> {
                var result = body.get();
                ScarpetNativeWork.record(result);
                return result;
            });
            ScarpetNativeWork.trackNative(player.carpetSpawnServer(), observed);
            ScarpetNativeWork.aliasDependency(actual, observed);
            ScarpetNativeWork.recoverGuestValue(observed).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)).whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }
}
