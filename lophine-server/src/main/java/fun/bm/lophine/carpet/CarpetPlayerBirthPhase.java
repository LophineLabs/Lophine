// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * A birth phase finishes after the actual body and every dynamically appended native child.
 */
public final class CarpetPlayerBirthPhase {
    private CarpetPlayerBirthPhase() {
    }

    public static CompletableFuture<Void> run(ServerPlayer player, Runnable body) {
        var lifetime = new CompletableFuture<Void>();
        ScarpetNativeWork.record(lifetime);
        var caller = ScarpetNativeWork.trackNative(player.carpetSpawnServer(), lifetime);
        try {
            var actual = run(player, body, 0);
            ScarpetNativeWork.aliasDependency(lifetime, actual);
            actual.whenComplete((ignored, failure) -> {
                if (failure == null) lifetime.complete(null);
                else lifetime.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            lifetime.completeExceptionally(failure);
        }
        return caller;
    }

    private static CompletableFuture<Void> run(ServerPlayer player, Runnable body, int migrations) {
        var captured = ScarpetRuntime.captureNativeContinuation(() -> {
            var world = player.level();
            var pos = player.blockPosition().immutable();
            var phase = ScarpetRuntime.captureNativeContinuation(() -> ScarpetNativeWork.<Void>observeNative(player, () -> {
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                    body.run();
                    return null;
                }
            }));
            return CarpetRegionLease.<CompletableFuture<Void>>runValue(world, (pos.getX() - 32) >> 4, (pos.getZ() - 32) >> 4, (pos.getX() + 32) >> 4, (pos.getZ() + 32) >> 4, lease -> {
                if (!TickThread.isTickThreadFor(player) || player.level() != world || !player.blockPosition().equals(pos)) {
                    if (migrations == 8)
                        throw new IllegalStateException("Player changed owner repeatedly during birth");
                    return run(player, body, migrations + 1);
                }
                return phase.get();
            }).thenCompose(next -> next);
        });
        var actual = ScarpetExplosionActors.entity(player, captured).thenCompose(next -> next);
        ScarpetNativeWork.record(actual);
        return actual;
    }
}
