// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.triggers.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Remaining foreign subtype comparisons use actual owners and retain the native evaluation order.
 */
public final class ScarpetCriterionRemainingMatchers {
    private ScarpetCriterionRemainingMatchers() {
    }

    private static CompletableFuture<Boolean> yes() {
        return CompletableFuture.completedFuture(true);
    }

    private static CompletableFuture<Boolean> no() {
        return CompletableFuture.completedFuture(false);
    }

    private static CompletableFuture<Boolean> and(CompletableFuture<Boolean> first, Supplier<CompletableFuture<Boolean>> second) {
        var next = ScarpetRuntime.captureNativeContinuation(second);
        return first.thenCompose(ScarpetRuntime.captureNativeFunction(passed -> passed ? next.get() : no()));
    }

    private static <T> CompletableFuture<T> job(ServerPlayer player, Supplier<CompletableFuture<T>> body) {
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
                var value = body.get();
                ScarpetNativeWork.record(value);
                return value;
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

    private record Source(ServerLevel world, Vec3 origin, ScarpetAttackEnchantments.SourceAdmission admission) {
    }

    private static Source source(ServerPlayer player) {
        ServerLevel world = player.level();
        return new Source(world, player.position(), new ScarpetAttackEnchantments.SourceAdmission(world, player.blockPosition(), world.getRandom(), player));
    }

    private static LootContext context(Source source, Entity entity) {
        // The original required THIS_ENTITY parameter is retained, including its native failure for a null pickup entity.
        var params = new LootParams.Builder(source.world()).withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, source.origin()).create(LootContextParamSets.ADVANCEMENT_ENTITY);
        var context = new LootContext.Builder(params).withOptionalRandomSource(source.admission().contextRandom()).create(Optional.empty());
        ScarpetLootRandomOwners.bind(context, source.admission());
        return context;
    }

    private static CompletableFuture<Boolean> item(ServerPlayer player, Optional<ItemPredicate> predicate, ItemStack actual) {
        return predicate.isEmpty() ? yes() : ScarpetLootConditions.actor(player, () -> predicate.get().test(actual));
    }

    public static CompletableFuture<Void> pickedUp(PickedUpItemTrigger trigger, ServerPlayer player, ItemStack actualItem, Entity entity) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(source(player), entity))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                        and(item(player, value.item(), actualItem), () -> ScarpetCriterionMatchers.condition(value.entity(), context))))));
    }

    public static CompletableFuture<Void> interacted(PlayerInteractTrigger trigger, ServerPlayer player, ItemStack actualItem, Entity entity) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(source(player), entity))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                        and(item(player, value.item(), actualItem), () -> ScarpetCriterionMatchers.condition(value.entity(), context))))));
    }

    public static CompletableFuture<Void> traded(TradeTrigger trigger, ServerPlayer player, AbstractVillager villager, ItemStack actualItem) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(source(player), villager))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                        and(ScarpetCriterionMatchers.condition(value.villager(), context), () -> item(player, value.item(), actualItem))))));
    }

    public static CompletableFuture<Void> fished(FishingRodHookedTrigger trigger, ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items) {
        var stable = List.copyOf(items);
        return job(player, () -> ScarpetLootConditions.actor(player, () -> source(player)).thenCompose(ScarpetRuntime.captureNativeFunction(source ->
                        ScarpetLootConditions.actor(hook, () -> hook.getHookedIn() != null ? hook.getHookedIn() : hook)
                                .thenCompose(ScarpetRuntime.captureNativeFunction(hooked -> ScarpetLootConditions.actor(player, () -> context(source, hooked))))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                        and(item(player, value.rod(), rod), () -> and(ScarpetCriterionMatchers.condition(value.entity(), context),
                                () -> fishingItem(player, value.item(), context, stable)))))));
    }

    private static CompletableFuture<Boolean> fishingItem(ServerPlayer player, Optional<ItemPredicate> predicate, LootContext context, List<ItemStack> items) {
        if (predicate.isEmpty()) return yes();
        Entity hooked = context.getOptional(LootContextParams.THIS_ENTITY);
        var matched = hooked instanceof ItemEntity actual ? ScarpetLootConditions.actor(actual, () -> predicate.get().test(actual.getItem())) : no();
        return matched.thenCompose(ScarpetRuntime.captureNativeFunction(passed -> fishingItems(player, predicate.get(), items, 0, passed)));
    }

    private static CompletableFuture<Boolean> fishingItems(ServerPlayer player, ItemPredicate predicate, List<ItemStack> items, int index, boolean alreadyMatched) {
        if (index == items.size()) return CompletableFuture.completedFuture(alreadyMatched);
        var next = ScarpetRuntime.captureNativeContinuation(() -> fishingItems(player, predicate, items, index + 1, alreadyMatched));
        // Vanilla still scans caught items when the hooked ItemEntity has already matched, stopping only at a caught-item match.
        return ScarpetLootConditions.actor(player, () -> predicate.test(items.get(index)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(passed -> passed ? yes() : next.get()));
    }

    private record Arrow(List<LootContext> contexts, int uniqueTypes) {
    }

    public static CompletableFuture<Void> arrow(KilledByArrowTrigger trigger, ServerPlayer player, Collection<Entity> victims, ItemStack actualWeapon) {
        var stable = List.copyOf(victims);
        return job(player, () -> ScarpetLootConditions.actor(player, () -> source(player))
                .thenCompose(ScarpetRuntime.captureNativeFunction(source -> arrowContexts(player, source, stable, 0, new ArrayList<>(), new HashSet<>())))
                .thenCompose(ScarpetRuntime.captureNativeFunction(arrow -> trigger.carpetTriggerNativeAsync(player, value ->
                        and(value.firedFromWeapon().isEmpty() ? yes() : actualWeapon == null ? no() : item(player, value.firedFromWeapon(), actualWeapon),
                                () -> and(distinctVictims(value.victims(), new ArrayList<>(arrow.contexts()), 0),
                                        () -> CompletableFuture.completedFuture(value.uniqueEntityTypes().matches(arrow.uniqueTypes()))))))));
    }

    private static CompletableFuture<Arrow> arrowContexts(ServerPlayer player, Source source, List<Entity> victims, int index,
                                                          List<LootContext> contexts, Set<EntityType<?>> types) {
        if (index == victims.size())
            return CompletableFuture.completedFuture(new Arrow(List.copyOf(contexts), types.size()));
        Entity victim = victims.get(index);
        var next = ScarpetRuntime.captureNativeContinuation(() -> arrowContexts(player, source, victims, index + 1, contexts, types));
        return ScarpetLootConditions.actor(victim, () -> {
                    types.add(victim.getType());
                    return (Void) null;
                })
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetLootConditions.actor(player, () -> {
                    contexts.add(context(source, victim));
                    return (Void) null;
                })))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }

    private static CompletableFuture<Boolean> distinctVictims(List<net.minecraft.core.Holder<net.minecraft.world.level.storage.loot.predicates.LootItemCondition>> predicates,
                                                              List<LootContext> remaining, int index) {
        return index == predicates.size() ? yes() : consumeVictim(predicates, remaining, index, 0);
    }

    private static CompletableFuture<Boolean> consumeVictim(List<net.minecraft.core.Holder<net.minecraft.world.level.storage.loot.predicates.LootItemCondition>> predicates,
                                                            List<LootContext> remaining, int predicateIndex, int victimIndex) {
        if (victimIndex == remaining.size()) return no();
        var next = ScarpetRuntime.captureNativeContinuation(() -> consumeVictim(predicates, remaining, predicateIndex, victimIndex + 1));
        return ScarpetLootConditions.test(predicates.get(predicateIndex).value(), remaining.get(victimIndex))
                .thenCompose(ScarpetRuntime.captureNativeFunction(passed -> {
                    if (!passed) return next.get();
                    remaining.remove(victimIndex);
                    return distinctVictims(predicates, remaining, predicateIndex + 1);
                }));
    }
}
