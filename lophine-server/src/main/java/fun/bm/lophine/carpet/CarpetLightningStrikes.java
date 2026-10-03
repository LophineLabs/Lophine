// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetLootActors;
import carpet.script.external.ScarpetLootConditions;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.WeakIdentityMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An accepted lightning pass completes each actual victim before hit bookkeeping and its criterion tail.
 */
public final class CarpetLightningStrikes {
    private static final WeakIdentityMap<LightningBolt, CompletableFuture<Void>> PENDING = new WeakIdentityMap<>();

    private CarpetLightningStrikes() {
    }

    public static boolean pending(LightningBolt lightning) {
        var work = PENDING.get(lightning);
        return work != null && !work.isDone();
    }

    public static CompletableFuture<Void> strike(ServerLevel admitted, LightningBolt lightning, List<Entity> entities, Runnable tail) {
        var actual = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        synchronized (PENDING) {
            if (pending(lightning)) throw new IllegalStateException("A lightning strike is already pending");
            PENDING.put(lightning, actual);
        }
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(admitted.getServer(), actual);
        actual.whenComplete((value, failure) -> PENDING.remove(lightning, actual));
        try {
            var body = ScarpetLootActors.jobNative(admitted, () -> {
                CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
                for (Entity target : List.copyOf(entities))
                    sequence = TisCommandContinuations.then(sequence, ignored ->
                            TisCommandContinuations.then(ScarpetLootConditions.actor(lightning, () -> (ServerLevel) lightning.level()), supplied ->
                                    ScarpetLootConditions.actor(target, () -> {
                                        target.thunderHit(supplied, lightning);
                                        return (Void) null;
                                    })));
                return TisCommandContinuations.then(sequence, ignored -> ScarpetLootConditions.actor(lightning, () -> {
                    tail.run();
                    return (Void) null;
                }));
            });
            ScarpetNativeWork.aliasDependency(actual, body);
            body.whenComplete((value, failure) -> {
                if (failure == null) actual.complete(null);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }
}
