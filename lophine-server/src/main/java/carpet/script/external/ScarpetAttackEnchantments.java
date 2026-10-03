// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.*;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.phys.Vec3;

/** Source-owned enchantments retain their exact weapon, source world, predicate context and affected entities. */
public final class ScarpetAttackEnchantments {
    private ScarpetAttackEnchantments() { }
    public record SourceAdmission(ServerLevel world, BlockPos callerPosition, RandomSource contextRandom, Entity actualCaller) {
        public SourceAdmission { Objects.requireNonNull(world); callerPosition = callerPosition.immutable(); Objects.requireNonNull(contextRandom); }
        public SourceAdmission(ServerLevel world, BlockPos callerPosition, RandomSource contextRandom) { this(world, callerPosition, contextRandom, null); }
    }
    private record Enchanted(Holder<Enchantment> enchantment, int level, EnchantedItemInUse item) { }

    static <T> CompletableFuture<T> caller(SourceAdmission admission, Supplier<T> body) {
        return admission.actualCaller() == null ? ScarpetLootActors.original(admission.world(), Vec3.atCenterOf(admission.callerPosition()), body)
            : ScarpetLootConditions.actor(admission.actualCaller(), body);
    }
    private static CompletableFuture<LootContext> context(SourceAdmission admission, int level, Entity victim, DamageSource source) {
        return ScarpetLootConditions.actor(victim, victim::position).thenCompose(ScarpetRuntime.captureNativeFunction(position -> caller(admission, () -> {
            var params = new LootParams.Builder(admission.world()).withParameter(LootContextParams.THIS_ENTITY, victim)
                .withParameter(LootContextParams.ENCHANTMENT_LEVEL, level).withParameter(LootContextParams.ORIGIN, position)
                .withParameter(LootContextParams.DAMAGE_SOURCE, source).withOptionalParameter(LootContextParams.ATTACKING_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity()).create(LootContextParamSets.ENCHANTED_DAMAGE);
            var context = new LootContext.Builder(params).withOptionalRandomSource(admission.contextRandom()).create(Optional.empty());
            ScarpetLootRandomOwners.bind(context, admission); return context;
        })));
    }
    private static List<Enchanted> item(ItemStack piece, EquipmentSlot slot, LivingEntity owner, Consumer<ItemStack> onBreak) {
        List<Enchanted> entries = new ArrayList<>();
        if (slot != null && piece.isEmpty()) return entries;
        var enchantments = piece.getOrDefault(DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        EnchantedItemInUse inUse = slot != null ? new EnchantedItemInUse(piece, slot, owner) : new EnchantedItemInUse(piece, null, owner, onBreak == null ? ignored -> { } : onBreak);
        for (var entry : enchantments.entrySet()) if (slot == null || entry.getKey().value().matchingSlot(slot))
            entries.add(new Enchanted(entry.getKey(), entry.getIntValue(), inUse));
        return List.copyOf(entries);
    }
    public static CompletableFuture<Float> modifyKnockback(SourceAdmission admission, ItemStack originalWeapon, Entity victim, DamageSource source, float initial) {
        return ScarpetLootActors.jobNative(admission.world(), () -> caller(admission, () -> item(originalWeapon, null, null, null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(enchantments -> values(enchantments, admission, victim, source, EnchantmentEffectComponents.KNOCKBACK, initial, 0))));
    }
    public static CompletableFuture<Float> modifyDamage(SourceAdmission admission, ItemStack originalWeapon, Entity victim, DamageSource source, float initial) {
        return ScarpetLootActors.jobNative(admission.world(), () -> caller(admission, () -> item(originalWeapon, null, null, null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(enchantments -> values(enchantments, admission, victim, source, EnchantmentEffectComponents.DAMAGE, initial, 0))));
    }
    public static CompletableFuture<Float> modifyFallBasedDamage(SourceAdmission admission,ItemStack originalWeapon,Entity victim,DamageSource source,float initial) {
        return ScarpetLootActors.jobNative(admission.world(),()->caller(admission,()->item(originalWeapon,null,null,null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(entries->values(entries,admission,victim,source,EnchantmentEffectComponents.SMASH_DAMAGE_PER_FALLEN_BLOCK,initial,0))));
    }
    private static CompletableFuture<Float> values(List<Enchanted> enchantments, SourceAdmission admission, Entity victim, DamageSource source, net.minecraft.core.component.DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>> component, float value, int index) {
        if (index == enchantments.size()) return CompletableFuture.completedFuture(value);
        Enchanted selected = enchantments.get(index);
        Supplier<CompletableFuture<Float>> next = ScarpetRuntime.captureNativeContinuation(() -> values(enchantments, admission, victim, source, component, value, index + 1));
        return context(admission, selected.level(), victim, source).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            valueEffects(selected.enchantment().value().getEffects(component), selected.level(), victim, context, value, 0)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(updated -> values(enchantments, admission, victim, source, component, updated, index + 1)));
    }
    private static CompletableFuture<Float> valueEffects(List<ConditionalEffect<EnchantmentValueEffect>> effects, int level, Entity randomOwner, LootContext context, float value, int index) {
        if (index == effects.size()) return CompletableFuture.completedFuture(value);
        var selected = effects.get(index);
        Supplier<CompletableFuture<Float>> next = ScarpetRuntime.captureNativeContinuation(() -> valueEffects(effects, level, randomOwner, context, value, index + 1));
        return ScarpetLootTables.condition(selected.requirements(), context).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> allowed
            ? ScarpetLootConditions.actor(randomOwner, () -> selected.effect().process(level, randomOwner.getRandom(), value))
                .thenCompose(ScarpetRuntime.captureNativeFunction(updated -> valueEffects(effects, level, randomOwner, context, updated, index + 1)))
            : next.get()));
    }
    public static CompletableFuture<Void> postAttack(SourceAdmission admission, Entity victim, DamageSource source, ItemStack originalWeapon, Consumer<ItemStack> attackerlessOnBreak) {
        return ScarpetLootActors.jobNative(admission.world(), () -> {
            var victimEquipment = victim instanceof LivingEntity living ? equipment(living, admission, victim, source, EnchantmentTarget.VICTIM, 0) : CompletableFuture.<Void>completedFuture(null);
            Supplier<CompletableFuture<Void>> attacker = ScarpetRuntime.captureNativeContinuation(() -> {
                if (originalWeapon == null) return CompletableFuture.completedFuture(null);
                if (source.getEntity() instanceof LivingEntity owner) return ScarpetLootConditions.actor(owner, () -> item(originalWeapon, EquipmentSlot.MAINHAND, owner, null))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(entries -> posts(entries, admission, victim, source, EnchantmentTarget.ATTACKER, 0)));
                if (attackerlessOnBreak == null) return CompletableFuture.completedFuture(null);
                return caller(admission, () -> item(originalWeapon, null, null, attackerlessOnBreak))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(entries -> posts(entries, admission, victim, source, EnchantmentTarget.ATTACKER, 0)));
            });
            return victimEquipment.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> attacker.get()));
        });
    }
    private static CompletableFuture<Void> equipment(LivingEntity owner, SourceAdmission admission, Entity victim, DamageSource source, EnchantmentTarget enchantedTarget, int slotIndex) {
        if (slotIndex == EquipmentSlot.VALUES_ARRAY.length) return CompletableFuture.completedFuture(null);
        EquipmentSlot slot = EquipmentSlot.VALUES_ARRAY[slotIndex];
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> equipment(owner, admission, victim, source, enchantedTarget, slotIndex + 1));
        return ScarpetLootConditions.actor(owner, () -> item(owner.getItemBySlot(slot), slot, owner, null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(entries -> posts(entries, admission, victim, source, enchantedTarget, 0)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }
    private static CompletableFuture<Void> posts(List<Enchanted> entries, SourceAdmission admission, Entity victim, DamageSource source, EnchantmentTarget enchantedTarget, int index) {
        if (index == entries.size()) return CompletableFuture.completedFuture(null);
        Enchanted selected = entries.get(index);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> posts(entries, admission, victim, source, enchantedTarget, index + 1));
        return postEffects(selected.enchantment().value().getEffects(EnchantmentEffectComponents.POST_ATTACK), selected, admission, victim, source, enchantedTarget, 0)
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }
    private static CompletableFuture<Void> postEffects(List<TargetedConditionalEffect<EnchantmentEntityEffect>> effects, Enchanted selected, SourceAdmission admission,
        Entity victim, DamageSource source, EnchantmentTarget enchantedTarget, int index) {
        if (index == effects.size()) return CompletableFuture.completedFuture(null);
        var effect = effects.get(index);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> postEffects(effects, selected, admission, victim, source, enchantedTarget, index + 1));
        if (effect.enchanted() != enchantedTarget) return next.get();
        return context(admission, selected.level(), victim, source).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            ScarpetLootTables.condition(effect.requirements(), context).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> {
                if (!allowed) return next.get();
                Entity affected = switch (effect.affected()) { case ATTACKER -> source.getEntity(); case DAMAGING_ENTITY -> source.getDirectEntity(); case VICTIM -> victim; };
                if (affected == null) return next.get();
                return ScarpetLootConditions.actor(affected, affected::position).thenCompose(ScarpetRuntime.captureNativeFunction(position ->
                    ScarpetEnchantmentEntityEffects.apply(effect.effect(), admission, selected.level(), selected.item(), affected, position)))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
            }))));
    }
    public static CompletableFuture<Float> equipmentDropChance(SourceAdmission admission, LivingEntity originalEntity, DamageSource source, float initial) {
        return ScarpetLootActors.jobNative(admission.world(), () -> chanceEquipment(originalEntity, originalEntity, admission, source, EnchantmentTarget.VICTIM, initial, 0)
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> source.getEntity() instanceof LivingEntity attacker
                ? chanceEquipment(attacker, originalEntity, admission, source, EnchantmentTarget.ATTACKER, value, 0)
                : CompletableFuture.completedFuture(value))));
    }
    private static CompletableFuture<Float> chanceEquipment(LivingEntity equipmentOwner, LivingEntity originalEntity, SourceAdmission admission,
        DamageSource source, EnchantmentTarget enchantedTarget, float chance, int slotIndex) {
        if (slotIndex == EquipmentSlot.VALUES_ARRAY.length) return CompletableFuture.completedFuture(chance);
        EquipmentSlot slot = EquipmentSlot.VALUES_ARRAY[slotIndex];
        return ScarpetLootConditions.actor(equipmentOwner, () -> item(equipmentOwner.getItemBySlot(slot), slot, equipmentOwner, null))
            .thenCompose(ScarpetRuntime.captureNativeFunction(entries -> chanceEnchantments(entries, originalEntity, admission, source, enchantedTarget, chance, 0)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> chanceEquipment(equipmentOwner, originalEntity, admission, source, enchantedTarget, value, slotIndex + 1)));
    }
    private static CompletableFuture<Float> chanceEnchantments(List<Enchanted> entries, LivingEntity originalEntity, SourceAdmission admission,
        DamageSource source, EnchantmentTarget enchantedTarget, float chance, int index) {
        if (index == entries.size()) return CompletableFuture.completedFuture(chance);
        Enchanted selected = entries.get(index);
        return context(admission, selected.level(), originalEntity, source).thenCompose(ScarpetRuntime.captureNativeFunction(context ->
            chanceEffects(selected.enchantment().value().getEffects(EnchantmentEffectComponents.EQUIPMENT_DROPS), selected.level(), originalEntity,
                context, enchantedTarget, chance, 0)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> chanceEnchantments(entries, originalEntity, admission, source, enchantedTarget, value, index + 1)));
    }
    private static CompletableFuture<Float> chanceEffects(List<TargetedConditionalEffect<EnchantmentValueEffect>> effects, int level, LivingEntity originalEntity,
        LootContext context, EnchantmentTarget enchantedTarget, float chance, int index) {
        if (index == effects.size()) return CompletableFuture.completedFuture(chance);
        var selected = effects.get(index);
        Supplier<CompletableFuture<Float>> next = ScarpetRuntime.captureNativeContinuation(() -> chanceEffects(effects, level, originalEntity, context, enchantedTarget, chance, index + 1));
        if (selected.enchanted() != enchantedTarget || selected.affected() != EnchantmentTarget.VICTIM) return next.get();
        return ScarpetLootTables.condition(selected.requirements(), context).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> !allowed ? next.get() :
            ScarpetLootConditions.actor(originalEntity, () -> selected.effect().process(level, originalEntity.getRandom(), chance))
                .thenCompose(ScarpetRuntime.captureNativeFunction(value -> chanceEffects(effects, level, originalEntity, context, enchantedTarget, value, index + 1)))));
    }
}
