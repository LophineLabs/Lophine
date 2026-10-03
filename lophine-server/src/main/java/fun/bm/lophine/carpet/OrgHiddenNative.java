// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetInteractionContinuations;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class OrgHiddenNative {
    private OrgHiddenNative() {
    }

    static <T> CompletableFuture<T> owner(ServerPlayer player, Supplier<T> work) {
        return owner(player, work, true);
    }

    static <T> CompletableFuture<T> snapshotOwner(ServerPlayer player, Supplier<T> work) {
        return owner(player, work, false);
    }

    private static <T> CompletableFuture<T> owner(ServerPlayer player, Supplier<T> work, boolean admitted) {
        var token = admitted ? ScarpetNativeWork.capture() : null;
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) {
            try {
                var attempt = admittedAttempt(player, work, admitted, token);
                if (attempt.completed())
                    return CarpetNativeActionContext.relay(admitted ? player : null, token, CompletableFuture.completedFuture(attempt.value()));
            } catch (Throwable failure) {
                return CarpetNativeActionContext.relay(admitted ? player : null, token, CompletableFuture.failedFuture(failure));
            }
        }
        var future = new CompletableFuture<T>();
        schedule(player, work, future, admitted, token);
        return CarpetNativeActionContext.relay(admitted ? player : null, token, future);
    }

    private static <T> void schedule(ServerPlayer player, Supplier<T> work, CompletableFuture<T> future, boolean admitted, ScarpetNativeWork.Token token) {
        boolean accepted = player.getBukkitEntity().taskScheduler.schedule(actor -> {
            try {
                if (actor != player || player.isRemoved())
                    throw new IllegalStateException("Hidden action player retired");
                var attempt = admittedAttempt(player, work, admitted, token);
                if (attempt.completed()) future.complete(attempt.value());
                else schedule(player, work, future, admitted, token);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }, retired -> future.completeExceptionally(new IllegalStateException("Hidden action player retired")), 1L);
        if (!accepted) future.completeExceptionally(new IllegalStateException("Hidden action scheduler retired"));
    }

    private static <T> OrgItemShadowGroups.Attempt<T> admittedAttempt(ServerPlayer player, Supplier<T> work, boolean admitted, ScarpetNativeWork.Token token) {
        return CarpetNativeActionContext.inNative(token, () -> {
            if (admitted) try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                return OrgItemShadowGroups.attempt(OrgItemShadowGroups.inventory(player, player.containerMenu.slots), work);
            }
            else
                try (var cleared = carpet.script.external.ScarpetPlayerInventoryGate.inheritAccepted(java.util.Set.of())) {
                    return OrgItemShadowGroups.attempt(OrgItemShadowGroups.inventory(player, player.containerMenu.slots), work);
                }
        });
    }

    static <T> CompletableFuture<T> laterOwner(ServerPlayer player, Supplier<T> work) {
        var token = ScarpetNativeWork.capture();
        var future = new CompletableFuture<T>();
        schedule(player, work, future, true, token);
        return CarpetNativeActionContext.relay(player, token, future);
    }

    static <T> CompletableFuture<T> ownerFuture(ServerPlayer player, Supplier<CompletableFuture<T>> work) {
        var token = ScarpetNativeWork.capture();
        return CarpetNativeActionContext.relay(player, token, owner(player, work).thenCompose(result -> result));
    }

    static <T> CompletableFuture<T> snapshotOwnerFuture(ServerPlayer player, Supplier<CompletableFuture<T>> work) {
        return CarpetNativeActionContext.relay(null, null, snapshotOwner(player, work).thenCompose(result -> result));
    }

    static <T> CompletableFuture<T> observed(ServerPlayer player, Supplier<T> work) {
        return ownerFuture(player, () -> ScarpetNativeWork.observeNative(player, () -> {
            try (var scope = ScarpetInteractionContinuations.open()) {
                return work.get();
            }
        }));
    }

    static CompletableFuture<InteractionResult> click(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        return observed(player, () -> {
            InteractionResult result = player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit);
            var actual = result instanceof InteractionResult.Deferred deferred ? deferred.plan().future() : CompletableFuture.completedFuture(result);
            ScarpetNativeWork.record(actual);
            return actual;
        }).thenCompose(result -> result);
    }

    static CompletableFuture<InteractionResult> use(ServerPlayer player, InteractionHand hand) {
        return observed(player, () -> {
            InteractionResult result = player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
            var actual = result instanceof InteractionResult.Deferred deferred ? deferred.plan().future() : CompletableFuture.completedFuture(result);
            ScarpetNativeWork.record(actual);
            return actual;
        }).thenCompose(result -> result);
    }

    static void swing(ServerPlayer player, InteractionHand hand) {
        player.swing(hand, SwingAnimation.DEFAULT, true);
    }
}
