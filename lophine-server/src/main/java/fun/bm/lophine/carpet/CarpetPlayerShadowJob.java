// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;

/**
 * Shutdown may abandon a waiting shadow, but must drain an admitted native placement.
 */
final class CarpetPlayerShadowJob {
    final CompletableFuture<Void> actual = new CompletableFuture<>();
    final CompletableFuture<Void> disconnected = new CompletableFuture<>();
    private boolean closed, placed;

    CarpetPlayerShadowJob(MinecraftServer server) {
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(server, actual);
        CarpetPlayerBirths.track(server, actual, this::stopWaiting);
    }

    void stopWaiting() {
        boolean abandon;
        synchronized (this) {
            closed = true;
            abandon = !placed;
        }
        if (abandon)
            disconnected.completeExceptionally(new IllegalStateException("Server stopped before shadow placement"));
    }

    synchronized void beginPlacement() {
        ensureOpen();
        placed = true;
    }

    synchronized void ensureOpen() {
        if (closed) throw new IllegalStateException("Server stopped before shadow placement");
    }

    void finish(CompletableFuture<?> chain) {
        ScarpetNativeWork.aliasDependency(actual, chain);
        chain.whenComplete((ignored, failure) -> {
            if (failure == null) actual.complete(null);
            else actual.completeExceptionally(failure);
        });
    }
}
