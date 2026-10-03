// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.DamagePredicate;
import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.phys.Vec3;

/** An ordered condition visitor; each native comparison uses the original context and the actual actor. */
public final class ScarpetLootConditions {
    private ScarpetLootConditions() { }

    public static CompletableFuture<Boolean> test(LootItemCondition condition, LootContext context) {
        if (condition instanceof AllOfCondition all) return terms(all.terms.stream().toList(), context, 0, true);
        if (condition instanceof AnyOfCondition any) return terms(any.terms.stream().toList(), context, 0, false);
        if (condition instanceof InvertedLootItemCondition inverted) return test(inverted.term().value(), context).thenApply(value -> !value);
        if (condition instanceof LootItemEntityPropertyCondition entity) {
            if (entity.predicate().isEmpty()) return CompletableFuture.completedFuture(true);
            return ScarpetEntityPredicates.matches(entity.predicate().get(), context.getLevel(), context.getOptional(LootContextParams.ORIGIN),
                context.getOptional(entity.entityTarget().contextParam()));
        }
        if (condition instanceof DamageSourceCondition damage) {
            DamageSource source = context.getOptional(LootContextParams.DAMAGE_SOURCE);
            Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
            if (source == null || origin == null) return CompletableFuture.completedFuture(false);
            return damage.predicate().isEmpty() ? CompletableFuture.completedFuture(true) : damage(damage.predicate().get(), context.getLevel(), origin, source);
        }
        if (condition instanceof LocationCheck location) {
            Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
            if (origin == null) return CompletableFuture.completedFuture(false);
            if (location.predicate().isEmpty()) return CompletableFuture.completedFuture(true);
            Vec3 position = origin.add(location.offset().getX(), location.offset().getY(), location.offset().getZ());
            return ScarpetLocationPredicates.matches(location.predicate().get(), context.getLevel(), position);
        }
        if (condition instanceof MatchBlock block) {
            var state = context.getOptional(LootContextParams.BLOCK_STATE);
            if (state == null || !block.predicate().matchesState(state)) return CompletableFuture.completedFuture(false);
            var entity = context.getOptional(LootContextParams.BLOCK_ENTITY);
            if (entity == null || !block.predicate().willMatchBlockEntity())
                return CompletableFuture.completedFuture(block.predicate().matchesBlockEntity(context.getLevel(), entity));
            // The original captured BlockEntity is compared at its block owner, independently of the caller's context RNG.
            var world = context.getLevel();
            var completed = ScarpetExplosionActors.world(world, entity.getBlockPos(), () -> {
                var observed = ScarpetNativeWork.observeNative(null, () -> block.predicate().matchesBlockEntity(world, entity));
                ScarpetNativeWork.trackNative(world.getServer(), observed); return ScarpetNativeWork.recoverGuestValue(observed);
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
            ScarpetNativeWork.record(completed); return completed;
        }
        if (condition instanceof EntityHasScoreCondition scores) {
            Entity entity = context.getOptional(scores.entityTarget().contextParam());
            if (entity == null) return CompletableFuture.completedFuture(false);
            return actor(entity, entity::getScoreboardName).thenCompose(ScarpetRuntime.captureNativeFunction(name ->
                scores(scores.scores().entrySet().stream().toList(), context, net.minecraft.world.scores.ScoreHolder.forNameOnly(name), 0)));
        }
        if (condition instanceof LootItemRandomChanceCondition random) return ScarpetLootNumbers.floating(random.chance().value(), context)
            .thenCompose(ScarpetRuntime.captureNativeFunction(chance -> original(context, () -> context.getRandom().nextFloat() < chance)));
        if (condition instanceof IntValueCheck value) return ScarpetLootNumbers.ranges(value.range(), context, value.value().value());
        if (condition instanceof FloatValueCheck value) return ScarpetLootNumbers.ranges(value.range(), context, value.value().value());
        if (condition instanceof TimeCheck time) return original(context, () -> {
            long ticks = context.getLevel().clockManager().getInstance(time.clock()).totalTicks();
            if (time.period().isPresent()) ticks %= time.period().get(); return (int)ticks;
        }).thenCompose(ScarpetRuntime.captureNativeFunction(ticks -> ScarpetLootNumbers.ranges(time.value(), context, ticks)));
        if (condition instanceof LootItemRandomChanceWithEnchantedBonusCondition enchanted) {
            Entity attacker = context.getOptional(LootContextParams.ATTACKING_ENTITY);
            if (attacker != null) return actor(attacker, () -> attacker instanceof net.minecraft.world.entity.LivingEntity living
                ? net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(enchanted.enchantment(), living) : 0)
                .thenCompose(ScarpetRuntime.captureNativeFunction(level -> original(context, () -> context.getRandom().nextFloat()
                    < (level > 0 ? enchanted.enchantedChance().calculate(level) : enchanted.unenchantedChance()))));
        }
        if (condition instanceof EnvironmentAttributeCheck<?> environment && environment.attribute().isPositional()) {
            Vec3 position = context.getOptional(LootContextParams.ORIGIN);
            return loaded(context, position == null ? Vec3.ZERO : position, () -> condition.test(context));
        }
        // Native non-spatial leaves consume this private context RNG and copied items in their original evaluation order.
        return original(context, () -> condition.test(context));
    }

    private static CompletableFuture<Boolean> scores(List<java.util.Map.Entry<String, net.minecraft.world.level.storage.loot.IntRangePredicate>> scores,
        LootContext context, net.minecraft.world.scores.ScoreHolder actualName, int index) {
        if (index == scores.size()) return CompletableFuture.completedFuture(true);
        var selected = scores.get(index);
        Supplier<CompletableFuture<Boolean>> next = ScarpetRuntime.captureNativeContinuation(() -> scores(scores, context, actualName, index + 1));
        return original(context, () -> {
            var board = context.getLevel().getScoreboard(); var objective = board.getObjective(selected.getKey());
            if (objective == null) return null; var value = board.getPlayerScoreInfo(actualName, objective); return value == null ? null : value.value();
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value == null ? CompletableFuture.completedFuture(false) :
            ScarpetLootNumbers.ranges(selected.getValue(), context, value).thenCompose(ScarpetRuntime.captureNativeFunction(passed -> passed ? next.get() : CompletableFuture.completedFuture(false)))));
    }

    private static CompletableFuture<Boolean> terms(List<Holder<LootItemCondition>> terms, LootContext context, int index, boolean all) {
        if (index == terms.size()) return CompletableFuture.completedFuture(all);
        Supplier<CompletableFuture<Boolean>> next = ScarpetRuntime.captureNativeContinuation(() -> terms(terms, context, index + 1, all));
        return test(terms.get(index).value(), context).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value == all ? next.get() : CompletableFuture.completedFuture(!all)));
    }

    public static CompletableFuture<Boolean> damage(DamageSourcePredicate predicate, ServerLevel originalWorld, Vec3 origin, DamageSource source) {
        for (var tag : predicate.tags()) if (!tag.matches(source.typeHolder())) return CompletableFuture.completedFuture(false);
        Supplier<CompletableFuture<Boolean>> sourceEntity = ScarpetRuntime.captureNativeContinuation(() -> predicate.sourceEntity().isEmpty()
            ? CompletableFuture.completedFuture(true) : ScarpetEntityPredicates.matches(predicate.sourceEntity().get(), originalWorld, origin, source.getEntity()));
        var direct = predicate.directEntity().isEmpty() ? CompletableFuture.completedFuture(true)
            : ScarpetEntityPredicates.matches(predicate.directEntity().get(), originalWorld, origin, source.getDirectEntity());
        return direct.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value ? sourceEntity.get() : CompletableFuture.completedFuture(false)))
            .thenApply(value -> value && (predicate.isDirect().isEmpty() || predicate.isDirect().get() == source.isDirect()));
    }

    public static CompletableFuture<Boolean> damage(DamageSourcePredicate predicate, ServerPlayer player, DamageSource source) {
        return actor(player, () -> new Origin(player.level(), player.position())).thenCompose(ScarpetRuntime.captureNativeFunction(origin ->
            damage(predicate, origin.world(), origin.position(), source)));
    }

    public static CompletableFuture<Boolean> damage(DamagePredicate predicate, ServerPlayer player, DamageSource source, float original, float actual, boolean blocked) {
        if (!predicate.dealtDamage().matches(original) || !predicate.takenDamage().matches(actual)) return CompletableFuture.completedFuture(false);
        return actor(player, () -> new Origin(player.level(), player.position())).thenCompose(ScarpetRuntime.captureNativeFunction(origin -> {
            var sourceEntity = predicate.sourceEntity().isEmpty() ? CompletableFuture.completedFuture(true)
                : ScarpetEntityPredicates.matches(predicate.sourceEntity().get(), origin.world(), origin.position(), source.getEntity());
            Supplier<CompletableFuture<Boolean>> type = ScarpetRuntime.captureNativeContinuation(() -> predicate.type().isEmpty()
                ? CompletableFuture.completedFuture(true) : damage(predicate.type().get(), player, source));
            return sourceEntity.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value && (predicate.blocked().isEmpty() || predicate.blocked().get() == blocked)
                ? type.get() : CompletableFuture.completedFuture(false)));
        }));
    }

    private record Origin(ServerLevel world, Vec3 position) { }
    public static <T> CompletableFuture<T> actor(Entity entity, Supplier<T> body) {
        var completed = ScarpetExplosionActors.entity(entity, () -> {
            var observed = ScarpetNativeWork.observeNative(entity, body);
            ScarpetNativeWork.trackNative(((ServerLevel)entity.level()).getServer(), observed);
            return ScarpetNativeWork.recoverGuestValue(observed);
        })
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(completed); return completed;
    }
    private static <T> CompletableFuture<T> original(LootContext context, Supplier<T> body) {
        if (ScarpetLootRandomOwners.bound(context)) return ScarpetLootRandomOwners.consume(context, body);
        var world = context.getLevel();
        // The same context RNG is consumed on its original world owner; no target-world RNG substitution.
        if (world == null) return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(null, body));
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        var actual = ScarpetExplosionActors.world(world, BlockPos.containing(origin == null ? Vec3.ZERO : origin),
            () -> {
                var observed = ScarpetNativeWork.observeNative(null, body); ScarpetNativeWork.trackNative(world.getServer(), observed);
                return ScarpetNativeWork.recoverGuestValue(observed);
            })
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(actual); return actual;
    }
    private static <T> CompletableFuture<T> loaded(LootContext context, Vec3 position, Supplier<T> body) {
        BlockPos block = BlockPos.containing(position);
        Supplier<CompletableFuture<T>> perform = ScarpetRuntime.captureNativeContinuation(() -> {
            var observed = ScarpetNativeWork.observeNative(null, body); ScarpetNativeWork.trackNative(context.getLevel().getServer(), observed);
            return ScarpetNativeWork.recoverGuestValue(observed);
        });
        var held = fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<T>>runLoadedValue(context.getLevel(),
            (block.getX() - 16) >> 4, (block.getZ() - 16) >> 4, (block.getX() + 16) >> 4, (block.getZ() + 16) >> 4, lease -> perform.get());
        var completed = held.thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)); ScarpetNativeWork.record(completed); return completed;
    }
}
