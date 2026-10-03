// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer.Event;
import carpet.script.value.EntityValue;
import carpet.script.value.StringValue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/** Preserves per-sender signed-message order while the real player_message callback decides cancellation. */
public final class ScarpetChatContinuations {
    private static final WeakIdentityMap<ServerPlayer, CompletableFuture<Void>> SERIAL = new WeakIdentityMap<>();
    private ScarpetChatContinuations() {}
    public static boolean defer(ServerPlayer sender, Object messageKey, String text, Runnable nativeBroadcast) {
        if (sender == null || !Event.PLAYER_MESSAGE.isNeeded() || ScarpetRuntime.EVENT_DISABLED.get() || ScarpetRuntime.isReplaying(messageKey)) return false;
        CompletableFuture<Void> tail = SERIAL.compute(sender, (key, previous) -> {
            CompletableFuture<Void> begin = previous == null ? CompletableFuture.completedFuture(null) : previous.handle((ignored, failure) -> null);
            return begin.thenCompose(ignored -> ScarpetRuntime.ownerEventDecision(Event.PLAYER_MESSAGE.handler,
                List.of(new EntityValue(sender), StringValue.of(text)), sender)).thenCompose(cancelled -> {
                    if (cancelled) return CompletableFuture.completedFuture(null);
                    return ScarpetRuntime.atEntityFuture(sender, () -> !sender.hasDisconnected()).thenCompose(connected -> connected
                        ? CompletableFuture.runAsync(() -> ScarpetRuntime.runReplaying(messageKey, nativeBroadcast))
                        : CompletableFuture.completedFuture(null));
                });
        });
        tail.whenComplete((ignored, failure) -> { SERIAL.remove(sender, tail); if (failure != null) carpet.script.CarpetScriptServer.LOG.error("Scarpet message phase failed", failure); });
        return true;
    }
}
