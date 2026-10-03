// SPDX-License-Identifier: MIT
package carpet.script.external;

import fun.bm.lophine.carpet.CarpetRegionLease;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

/** Typed native subtype matchers retain source short circuits and context construction order. */
public final class ScarpetCriterionMatchers {
    private ScarpetCriterionMatchers() { }
    private static CompletableFuture<Boolean> yes() { return CompletableFuture.completedFuture(true); }
    private static CompletableFuture<Boolean> no() { return CompletableFuture.completedFuture(false); }
    private static CompletableFuture<Boolean> and(CompletableFuture<Boolean> first, Supplier<CompletableFuture<Boolean>> second) {
        var next = ScarpetRuntime.captureNativeContinuation(second);
        return first.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value ? next.get() : no()));
    }
    private static CompletableFuture<Boolean> or(CompletableFuture<Boolean> first, Supplier<CompletableFuture<Boolean>> second) {
        var next = ScarpetRuntime.captureNativeContinuation(second);
        return first.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value ? yes() : next.get()));
    }
    static CompletableFuture<Boolean> condition(Optional<Holder<LootItemCondition>> predicate, LootContext context) {
        return predicate.isEmpty() ? yes() : context == null ? no() : ScarpetLootConditions.test(predicate.get().value(), context);
    }
    private static LootContext context(ServerPlayer player, Entity entity) {
        if (entity == null) return null;
        var context = EntityPredicate.createContext(player, entity);
        ScarpetLootRandomOwners.bind(context, new ScarpetAttackEnchantments.SourceAdmission(player.level(), player.blockPosition(), context.getRandom(), player));
        return context;
    }
    private static <T> CompletableFuture<T> job(ServerPlayer player, Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        ScarpetNativeWork.record(actual);
        try {
            ScarpetNativeWork.trackNative(player.carpetSpawnServer(), actual);
            var observed = ScarpetNativeWork.observeNative(player, () -> { var work = body.get(); ScarpetNativeWork.record(work); return work; });
            ScarpetNativeWork.trackNative(player.carpetSpawnServer(), observed); ScarpetNativeWork.aliasDependency(actual, observed);
            ScarpetNativeWork.recoverGuestValue(observed).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)).whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value); else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        return actual;
    }

    public static CompletableFuture<Void> bred(BredAnimalsTrigger trigger, ServerPlayer player, Animal parent, Animal partner, AgeableMob child) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> Arrays.asList(context(player, parent), context(player, partner), context(player, child)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(contexts -> trigger.carpetTriggerNativeAsync(player, value ->
                and(condition(value.child(), contexts.get(2)), () -> or(
                    and(condition(value.parent(), contexts.get(0)), () -> condition(value.partner(), contexts.get(1))),
                    () -> and(condition(value.parent(), contexts.get(1)), () -> condition(value.partner(), contexts.get(0)))))))));
    }
    public static CompletableFuture<Void> cured(CuredZombieVillagerTrigger trigger, ServerPlayer player, Zombie zombie, Villager villager) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> List.of(context(player, zombie), context(player, villager)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(contexts -> trigger.carpetTriggerNativeAsync(player, value ->
                and(condition(value.zombie(), contexts.get(0)), () -> condition(value.villager(), contexts.get(1)))))));
    }
    public static CompletableFuture<Void> effects(EffectsChangedTrigger trigger, ServerPlayer player, Entity source) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, source))
            .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                and(ScarpetLootConditions.actor(player, () -> value.effects().isEmpty() || value.effects().get().matches(player)),
                    () -> condition(value.source(), context))))));
    }
    public static CompletableFuture<Void> summoned(SummonedEntityTrigger trigger, ServerPlayer player, Entity entity) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, entity))
            .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value -> condition(value.entity(), context)))));
    }
    public static CompletableFuture<Void> tamed(TameAnimalTrigger trigger, ServerPlayer player, Animal entity) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, entity))
            .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value -> condition(value.entity(), context)))));
    }
    public static CompletableFuture<Void> target(TargetBlockTrigger trigger, ServerPlayer player, Entity projectile, int signal) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> context(player, projectile))
            .thenCompose(ScarpetRuntime.captureNativeFunction(context -> trigger.carpetTriggerNativeAsync(player, value ->
                value.signalStrength().matches(signal) ? condition(value.projectile(), context) : no()))));
    }
    public static CompletableFuture<Void> channeled(ChanneledLightningTrigger trigger, ServerPlayer player, Collection<? extends Entity> victims) {
        var stable = List.copyOf(victims);
        return job(player, () -> ScarpetLootConditions.actor(player, () -> stable.stream().map(entity -> context(player, entity)).toList())
            .thenCompose(ScarpetRuntime.captureNativeFunction(contexts -> trigger.carpetTriggerNativeAsync(player, value -> eachVictim(value.victims(), contexts, 0)))));
    }
    private static CompletableFuture<Boolean> eachVictim(List<Holder<LootItemCondition>> predicates, List<LootContext> victims, int index) {
        if (index == predicates.size()) return yes();
        return and(any(predicates.get(index), victims, 0), () -> eachVictim(predicates, victims, index + 1));
    }
    private static CompletableFuture<Boolean> any(Holder<LootItemCondition> predicate, List<LootContext> victims, int index) {
        return index == victims.size() ? no() : or(ScarpetLootConditions.test(predicate.value(), victims.get(index)), () -> any(predicate, victims, index + 1));
    }
    private record Lightning(List<LootContext> around, LootContext bolt) { }
    public static CompletableFuture<Void> lightning(LightningStrikeTrigger trigger, ServerPlayer player, LightningBolt lightning, List<Entity> entitiesAround) {
        var stable = List.copyOf(entitiesAround);
        return job(player, () -> ScarpetLootConditions.actor(player, () -> new Lightning(stable.stream().map(entity -> context(player, entity)).toList(), context(player, lightning)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(contexts -> trigger.carpetTriggerNativeAsync(player, value ->
                and(condition(value.lightning(), contexts.bolt()), () -> value.bystander().isEmpty() ? yes() : any(value.bystander().get(), contexts.around(), 0))))));
    }

    private record Position(ServerLevel world, Vec3 current, LootContext cause) { }
    private static CompletableFuture<Boolean> location(Optional<LocationPredicate> predicate, ServerLevel world, Vec3 origin) {
        return predicate.isEmpty() ? yes() : ScarpetLocationPredicates.matches(predicate.get(), world, origin);
    }
    public static CompletableFuture<Void> distance(DistanceTrigger trigger, ServerPlayer player, Vec3 start) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> new Position(player.level(), player.position(), null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(position -> trigger.carpetTriggerNativeAsync(player, value ->
                and(location(value.startPosition(), position.world(), start), () -> CompletableFuture.completedFuture(value.distance().isEmpty() || value.distance().get()
                    .matches(start.x, start.y, start.z, position.current().x, position.current().y, position.current().z)))))));
    }
    public static CompletableFuture<Void> fall(FallAfterExplosionTrigger trigger, ServerPlayer player, Vec3 start, Entity cause) {
        return job(player, () -> ScarpetLootConditions.actor(player, () -> new Position(player.level(), player.position(), context(player, cause)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(position -> trigger.carpetTriggerNativeAsync(player, value ->
                and(location(value.startPosition(), position.world(), start), () -> value.distance().isEmpty() || value.distance().get()
                    .matches(start.x, start.y, start.z, position.current().x, position.current().y, position.current().z)
                        ? condition(value.cause(), position.cause()) : no())))));
    }

    private record BlockSource(ServerLevel world, ScarpetAttackEnchantments.SourceAdmission admission) { }
    private record BlockSnapshot(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.entity.BlockEntity entity) { }
    private static CompletableFuture<LootContext> blockContext(ServerPlayer player, BlockPos position, ItemInstance tool, boolean defaultUse) {
        BlockPos pos = position.immutable();
        return ScarpetLootConditions.actor(player, () -> new BlockSource(player.level(), new ScarpetAttackEnchantments.SourceAdmission(
            player.level(), player.blockPosition(), player.level().getRandom(), player))).thenCompose(ScarpetRuntime.captureNativeFunction(source -> {
            ServerLevel world = source.world();
            var phase = ScarpetRuntime.captureNativeContinuation(() -> {
                var observed = ScarpetNativeWork.observeNative(null, () -> new BlockSnapshot(world.getBlockState(pos), defaultUse ? null : world.getBlockEntity(pos)));
                ScarpetNativeWork.trackNative(world.getServer(), observed); return ScarpetNativeWork.recoverGuestValue(observed);
            });
            // Only the original native block access loads its chunk; surrounding topology stays loaded-only.
            var held = ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, pos)
                ? CompletableFuture.completedFuture(phase.get())
                : CarpetRegionLease.<CompletableFuture<BlockSnapshot>>runLoadedValue(world, (pos.getX()-16)>>4, (pos.getZ()-16)>>4,
                    (pos.getX()+16)>>4, (pos.getZ()+16)>>4, lease -> phase.get());
            var result = held.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)).thenCompose(ScarpetRuntime.captureNativeFunction(block ->
                ScarpetLootConditions.actor(player, () -> {
                    var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.THIS_ENTITY, player).withParameter(LootContextParams.BLOCK_STATE, block.state());
                    if (!defaultUse) params.withOptionalParameter(LootContextParams.BLOCK_ENTITY, block.entity()).withParameter(LootContextParams.TOOL, tool);
                    var context = new LootContext.Builder(params.create(defaultUse ? LootContextParamSets.BLOCK_USE : LootContextParamSets.ADVANCEMENT_LOCATION))
                        .withOptionalRandomSource(source.admission().contextRandom()).create(Optional.empty());
                    ScarpetLootRandomOwners.bind(context, source.admission()); return context;
                })));
            ScarpetNativeWork.record(result); return result;
        }));
    }
    public static CompletableFuture<Void> used(ItemUsedOnLocationTrigger trigger, ServerPlayer player, BlockPos pos, ItemInstance tool) {
        return job(player, () -> blockContext(player, pos, tool, false).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            trigger.carpetTriggerNativeAsync(player, value -> condition(value.location(), context)))));
    }
    public static CompletableFuture<Void> anyBlock(AnyBlockInteractionTrigger trigger, ServerPlayer player, BlockPos pos, ItemInstance tool) {
        return job(player, () -> blockContext(player, pos, tool, false).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            trigger.carpetTriggerNativeAsync(player, value -> condition(value.location(), context)))));
    }
    public static CompletableFuture<Void> defaultBlock(DefaultBlockInteractionTrigger trigger, ServerPlayer player, BlockPos pos) {
        return job(player, () -> blockContext(player, pos, null, true).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            trigger.carpetTriggerNativeAsync(player, value -> condition(value.location(), context)))));
    }
}
