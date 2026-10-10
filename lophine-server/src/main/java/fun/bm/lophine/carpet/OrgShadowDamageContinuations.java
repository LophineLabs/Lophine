// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Better-totem inventory custody covers the true whole hurt body and its native completion.
 */
public final class OrgShadowDamageContinuations {
    private OrgShadowDamageContinuations() {
    }

    public static boolean hurt(ServerPlayer player, Supplier<Boolean> nativeBody) {
        if ("vanilla".equals(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.betterTotemOfUndying) || OrgItemShadowGroups.nativeLoanFor(player))
            return nativeBody.get();
        var job = new Admission(player, nativeBody);
        ScarpetNativeWork.record(job.actual);
        ScarpetNativeWork.trackNative(player.level().getServer(), job.actual);
        try {
            job.start();
        } catch (Throwable failure) {
            job.actual.completeExceptionally(failure);
        }
        ScarpetDamageContinuations.publishBodyResult(player, job.actual);
        return job.actual.isDone() && !job.actual.isCompletedExceptionally() ? job.actual.getNow(false) : false;
    }

    private static final class Admission {
        final ServerPlayer player;
        final Supplier<Boolean> body;
        final CompletableFuture<Boolean> actual = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        final Supplier<Void> continuation;

        Admission(ServerPlayer player, Supplier<Boolean> body) {
            this.player = player;
            this.body = body;
            continuation = ScarpetRuntime.captureNativeContinuation(() -> {
                try {
                    start();
                } catch (Throwable failure) {
                    actual.completeExceptionally(failure);
                }
                return null;
            });
        }

        void start() {
            if (actual.isDone()) return;
            if (ScarpetPlayerInventoryGate.paused(player)) {
                ScarpetPlayerInventoryGate.whenOpen(player).whenComplete(ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
                    if (failure != null) actual.completeExceptionally(failure);
                    else schedule();
                }));
                return;
            }
            var borrowed = OrgItemShadowGroups.attemptNative(player, OrgItemShadowGroups.inventory(player, player.containerMenu.slots), () -> {
                // An unavailable loan is an intent. Only the acquired body can hold a
                // snapshot gate; otherwise a snapshot could wait on its own group release.
                ScarpetPlayerInventoryGate.trackAccepted(player, actual);
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                    return ScarpetDamageContinuations.observeNativeBody(player, () -> {
                        // Native death may reach its removal gate before this call returns.
                        ScarpetNativeWork.aliasDependency(actual,
                                ScarpetNativeWork.completionOf(ScarpetNativeWork.capture()));
                        return body.get();
                    });
                }
            });
            if (!borrowed.completed()) {
                schedule();
                return;
            }
            ScarpetNativeWork.aliasDependency(actual, borrowed.value());
            borrowed.value().whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) -> {
                if (failure == null) actual.complete(value);
                else actual.completeExceptionally(failure);
            }));
        }

        void schedule() {
            try {
                boolean queued = player.getBukkitEntity().taskScheduler.schedule(owned -> {
                    if (owned != player || player.isRemoved()) {
                        actual.completeExceptionally(new IllegalStateException("Queued better-totem player retired"));
                        return;
                    }
                    continuation.get();
                }, retired -> actual.completeExceptionally(new IllegalStateException("Queued better-totem player retired")), 1L);
                if (!queued)
                    actual.completeExceptionally(new IllegalStateException("Queued better-totem scheduler retired"));
            } catch (Throwable failure) {
                actual.completeExceptionally(failure);
            }
        }
    }
}
