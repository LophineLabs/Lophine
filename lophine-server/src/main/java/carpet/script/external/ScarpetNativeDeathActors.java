// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Real native death recipients run on their own actors and finish their dynamic work in source order.
 */
public final class ScarpetNativeDeathActors {
    public record Loot(Player player, float luck) {
    }

    private record LootScope(LivingEntity target, Loot value) {
    }

    private static final ThreadLocal<LootScope> LOOT = new ThreadLocal<>();

    private ScarpetNativeDeathActors() {
    }

    /**
     * A returned native continuation remains part of the actor body through its final tail.
     */
    private static <T> T nativeValue(Supplier<T> operation) {
        T value = operation.get();
        if (value instanceof CompletableFuture<?> continuation) ScarpetNativeWork.record(continuation);
        return value;
    }

    public static Loot currentLoot(LivingEntity target) {
        var scope = LOOT.get();
        return scope != null && scope.target() == target ? scope.value() : null;
    }

    private static <T> T withLoot(LivingEntity target, Loot value, Supplier<T> operation) {
        var previous = LOOT.get();
        LOOT.set(new LootScope(target, value));
        try {
            return operation.get();
        } finally {
            if (previous == null) LOOT.remove();
            else LOOT.set(previous);
        }
    }

    public static <T extends Entity> CompletableFuture<T> resolve(LivingEntity owner, EntityReference<T> reference, Class<T> type) {
        if (reference == null) return CompletableFuture.completedFuture(null);
        ServerLevel world = (ServerLevel) owner.level();
        T candidate = reference.carpetCandidate(world, type);
        if (candidate == null) return CompletableFuture.completedFuture(null);
        Supplier<CompletableFuture<T>> fallback = ScarpetRuntime.captureNativeContinuation(() ->
                ScarpetExplosionActors.entity(owner, () -> reference.carpetLookup(world, type)).thenCompose(ScarpetRuntime.captureNativeFunction(resolved -> {
                    if (resolved == null || resolved == candidate) return CompletableFuture.completedFuture(null);
                    return entity(resolved, () -> resolved.isRemoved() ? null : resolved);
                })));
        return entity(candidate, () -> candidate.isRemoved()).thenCompose(ScarpetRuntime.captureNativeFunction(removed ->
                removed ? fallback.get() : CompletableFuture.completedFuture(candidate)));
    }

    public static <T> CompletableFuture<T> entity(Entity recipient, Supplier<T> operation) {
        return ScarpetExplosionActors.entity(recipient, () -> {
            var observed = ScarpetNativeWork.observeNative(recipient, () -> nativeValue(operation));
            return ScarpetNativeWork.recoverGuestValue(observed);
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
    }

    public static <T> CompletableFuture<T> target(LivingEntity recipient, Supplier<T> operation) {
        return target(recipient, null, operation, 0);
    }

    public static <T> CompletableFuture<T> target(LivingEntity recipient, BlockPos extra, Supplier<T> operation) {
        return target(recipient, extra, operation, 0);
    }

    private static <T> CompletableFuture<T> target(LivingEntity recipient, BlockPos extra, Supplier<T> operation, int attempt) {
        if (attempt == 8)
            return CompletableFuture.failedFuture(new IllegalStateException("Native death target kept changing regions"));
        return ScarpetExplosionActors.entity(recipient, () -> {
            ServerLevel world = (ServerLevel) recipient.level();
            BlockPos pos = recipient.blockPosition().immutable();
            int minX = ((extra == null ? pos.getX() : Math.min(pos.getX(), extra.getX())) - 32) >> 4;
            int minZ = ((extra == null ? pos.getZ() : Math.min(pos.getZ(), extra.getZ())) - 32) >> 4;
            int maxX = ((extra == null ? pos.getX() : Math.max(pos.getX(), extra.getX())) + 32) >> 4;
            int maxZ = ((extra == null ? pos.getZ() : Math.max(pos.getZ(), extra.getZ())) + 32) >> 4;
            Supplier<CompletableFuture<T>> perform = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(recipient, () -> {
                BlockPos now = recipient.blockPosition();
                if (recipient.level() != world || ((now.getX() - 32) >> 4) < minX || ((now.getX() + 32) >> 4) > maxX
                        || ((now.getZ() - 32) >> 4) < minZ || ((now.getZ() + 32) >> 4) > maxZ)
                    return target(recipient, extra, operation, attempt + 1);
                var observed = ScarpetNativeWork.observeNative(recipient, () -> nativeValue(operation));
                return ScarpetNativeWork.recoverGuestValue(observed);
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<T>>runOwnedPhaseValue(world, minX, minZ, maxX, maxZ, lease -> perform.get())
                    .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
    }

    public static <T> CompletableFuture<T> loot(LivingEntity target, Supplier<T> operation) {
        return ScarpetExplosionActors.entity(target, target::carpetLootPlayerAsync).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value))
                .thenCompose(ScarpetRuntime.captureNativeFunction(player -> {
                    var luck = player == null ? CompletableFuture.completedFuture(new Loot(null, 0)) : entity(player, () -> new Loot(player, player.getLuck()));
                    return luck.thenCompose(ScarpetRuntime.captureNativeFunction(value -> target(target, () -> withLoot(target, value, operation))));
                }));
    }

    public static CompletableFuture<Void> award(LivingEntity killer, Entity victim, DamageSource source) {
        return killer == null ? CompletableFuture.completedFuture(null) : entity(killer, () -> {
            killer.awardKillScore(victim, source);
            return null;
        });
    }

    public static CompletableFuture<Boolean> preKilled(Entity sourceEntity, ServerLevel originalWorld, LivingEntity victim, DamageSource source) {
        if (sourceEntity == null) return CompletableFuture.completedFuture(true);
        if (sourceEntity instanceof net.minecraft.world.entity.monster.Creeper creeper)
            return entity(creeper, () -> creeper.carpetKilledEntityPreAsync(originalWorld, victim, source)).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        return entity(sourceEntity, () -> sourceEntity.killedEntityPreEvent(originalWorld, victim, source));
    }

    public static CompletableFuture<Boolean> killed(Entity sourceEntity, ServerLevel originalWorld, LivingEntity victim, DamageSource source) {
        if (sourceEntity == null) return CompletableFuture.completedFuture(true);
        if (sourceEntity instanceof net.minecraft.world.entity.monster.zombie.Zombie zombie)
            return entity(zombie, () -> zombie.carpetKilledEntityAsync(originalWorld, victim, source)).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        return entity(sourceEntity, () -> sourceEntity.killedEntity(originalWorld, victim, source));
    }
}
