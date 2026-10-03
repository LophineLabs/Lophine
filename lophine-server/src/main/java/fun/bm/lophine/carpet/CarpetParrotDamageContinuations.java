// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Original left draw/release, right draw/release, then the real damage body. */
public final class CarpetParrotDamageContinuations {
    private CarpetParrotDamageContinuations() { }
    public static CompletableFuture<Boolean> hurt(ServerPlayer player, float damage, Supplier<Boolean> remainder) {
        var actual = new CompletableFuture<Boolean>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        ScarpetNativeWork.record(actual); ScarpetNativeWork.trackNative(player.carpetSpawnServer(), actual);
        ScarpetDamageContinuations.publishBodyResult(player, actual);
        try {
            double chance = damage / 15.0D;
            var left = OrgMenuNativeEffects.run(player, () -> {
                ScarpetPlayerInventoryGate.trackAccepted(player, actual);
                CompletableFuture<Entity> release = player.getRandom().nextFloat() < chance
                    ? player.carpetReleaseShoulderNativeAsync(true) : CompletableFuture.completedFuture(null);
                ScarpetNativeWork.record(release); return release;
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
            ScarpetNativeWork.record(left);
            var body = TisCommandContinuations.then(left, ignored -> right(player, chance, remainder));
            ScarpetNativeWork.record(body); ScarpetNativeWork.aliasDependency(actual, body);
            body.whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value); else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        var caller = actual.copy(); ScarpetNativeWork.aliasDependency(caller, actual); return caller;
    }
    private static CompletableFuture<Boolean> right(ServerPlayer player, double chance, Supplier<Boolean> remainder) {
        var selected = OrgMenuNativeEffects.run(player, () -> {
            CompletableFuture<Entity> release = player.getRandom().nextFloat() < chance
                ? player.carpetReleaseShoulderNativeAsync(false) : CompletableFuture.completedFuture(null);
            ScarpetNativeWork.record(release); return release;
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(selected);
        return TisCommandContinuations.then(selected, ignored -> {
            var body = OrgMenuNativeEffects.run(player, () -> {
                var nativeBody = ScarpetDamageContinuations.observeNativeBody(player, remainder);
                ScarpetNativeWork.record(nativeBody); return nativeBody;
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
            ScarpetNativeWork.record(body); return body;
        });
    }
}
