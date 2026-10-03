// SPDX-License-Identifier: LGPL-3.0-or-later
package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

/** Source Boolean belongs to the complete dragon hurt, including its real nested living damage. */
public final class ScarpetRenewableDragonHead {
    private static final WeakIdentityMap<EnderDragon,CompletableFuture<Boolean>> OUTCOMES = new WeakIdentityMap<>();
    private ScarpetRenewableDragonHead() { }
    public static CompletableFuture<Boolean> pendingResult(EnderDragon dragon) { return OUTCOMES.get(dragon); }
    public static CompletableFuture<Boolean> hurt(EnderDragon dragon, ServerLevel original, EnderDragonPart part, DamageSource source, float damage) {
        var actual = new CompletableFuture<Boolean>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        OUTCOMES.put(dragon, actual); ScarpetNativeWork.record(actual); ScarpetNativeWork.trackNative(original.getServer(), actual);
        var body = ScarpetLootActors.jobNative(original, () -> ScarpetNativeDeathActors.entity(dragon, () -> dragon.carpetHurtPrefix(part, source, damage))
            .thenCompose(ScarpetRuntime.captureNativeFunction(prefix -> {
                if (prefix == null) return CompletableFuture.completedFuture(false);
                if (!prefix.qualified()) return CompletableFuture.completedFuture(true);
                return damage(dragon, original, source, prefix.damage()).thenApply(ScarpetRuntime.captureNativeFunction(ignored -> true));
            })));
        ScarpetNativeWork.aliasDependency(actual, body);
        body.whenComplete((value, failure) -> {
            if (failure == null) actual.complete(value); else actual.completeExceptionally(failure);
            OUTCOMES.remove(dragon, actual);
        });
        return actual;
    }
    public static CompletableFuture<Void> damage(EnderDragon dragon, ServerLevel original, DamageSource source, float damage) {
        return ScarpetNativeDeathActors.entity(dragon, () -> dragon.carpetHeadReallyHurt(original, source, damage))
            .thenCompose(ScarpetRuntime.captureNativeFunction(healthBefore -> ScarpetNativeDeathActors.entity(dragon, () -> dragon.carpetHeadCandidate(source))
                .thenCompose(ScarpetRuntime.captureNativeFunction(creeper -> creeper == null ? CompletableFuture.completedFuture(false)
                    : ScarpetNativeDeathActors.entity(creeper, creeper::carpetReserveDragonHeadDrop)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(reserved -> ScarpetNativeDeathActors.entity(dragon, () -> {
                    dragon.carpetHeadAfterDamage(reserved, healthBefore); return (Void)null;
                })))));
    }
}
