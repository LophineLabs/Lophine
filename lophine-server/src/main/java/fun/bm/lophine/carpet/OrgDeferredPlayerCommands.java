// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * A persistence command requested by a native callback starts after that callback can return.
 */
final class OrgDeferredPlayerCommands {
    private OrgDeferredPlayerCommands() {
    }

    static boolean deferIfCausal(ServerPlayer player, Runnable command, Consumer<Throwable> failed) {
        if (!CarpetAsyncCommandResults.hasNativeCause()) return false;
        defer(player, command, failed);
        return true;
    }

    /**
     * Starts an external snapshot after the current native callback has released its owner.
     */
    static void defer(ServerPlayer player, Runnable command, Consumer<Throwable> failed) {
        var detached = carpet.script.external.ScarpetRuntime.captureDetachedNativeContinuation(() ->
                CarpetNativeActionContext.with(null, () -> {
                    command.run();
                    return null;
                }));
        boolean scheduled;
        try {
            scheduled = player.getBukkitEntity().taskScheduler.schedule(owner -> {
                try {
                    detached.get();
                } catch (Throwable failure) {
                    failed.accept(failure);
                }
            }, retired -> failed.accept(new IllegalStateException("Fake player retired before the deferred command")), 1L);
        } catch (Throwable failure) {
            failed.accept(failure);
            return;
        }
        if (!scheduled) failed.accept(new IllegalStateException("Fake-player command scheduler retired"));
    }
}
