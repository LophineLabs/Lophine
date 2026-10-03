// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;

/** One real original-world beacon pulse retains every actual recipient and its dynamic effect tail. */
public final class OrgBeaconEffects {
    private OrgBeaconEffects() {}
    @FunctionalInterface public interface Effect { void apply(ServerPlayer player, ServerLevel sourceWorld, BlockPos source, boolean primary); }

    public static CompletableFuture<Void> applyNative(ServerLevel originalWorld, BlockPos origin, AABB bounds,
        boolean secondary, Effect effect) {
        BlockPos original = origin.immutable();
        var actual = new CompletableFuture<Void>();
        ScarpetNativeWork.record(actual); var caller = ScarpetNativeWork.trackNative(originalWorld.getServer(), actual);
        try {
            var observed = ScarpetNativeWork.observeNative(null, () -> {
                var recipients = new ArrayList<CompletableFuture<Void>>();
                for (ServerPlayer player : originalWorld.getServer().getPlayerList().getPlayers()) {
                    recipients.add(ScarpetExplosionActors.admitTarget(player, () ->
                        TisCommandContinuations.then(TisCommandContinuations.owned(player, () ->
                            !player.isRemoved() && player.level() == originalWorld && EntitySelector.NO_SPECTATORS.test(player)
                                && player.getBoundingBox().intersects(bounds)), eligible -> {
                            if (!eligible) return CompletableFuture.completedFuture(null);
                            var primary = TisCommandContinuations.owned(player, () -> { effect.apply(player, originalWorld, original, true); return null; });
                            return TisCommandContinuations.then(primary, ignored -> secondary
                                ? TisCommandContinuations.owned(player, () -> { effect.apply(player, originalWorld, original, false); return null; })
                                : CompletableFuture.completedFuture(null));
                        })));
                }
                var completed = CompletableFuture.allOf(recipients.toArray(CompletableFuture[]::new));
                ScarpetNativeWork.record(completed); return completed;
            });
            ScarpetNativeWork.trackNative(originalWorld.getServer(), observed); ScarpetNativeWork.aliasDependency(actual, observed);
            ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value).whenComplete((ignored, failure) -> {
                if (failure == null) actual.complete(null); else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        return caller;
    }
}
