// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Org command receipts retain their real file, teleport and recipient effects.
 */
final class OrgCommandNativeEffects {
    private OrgCommandNativeEffects() {
    }

    static int command(CommandSourceStack source, int immediate, Supplier<CompletableFuture<Integer>> action) {
        return TisCommandContinuations.complete(source, immediate,
                () -> OrgMenuNativeEffects.admit(source.getServer(), action), null, () -> {
                });
    }

    static CompletableFuture<Void> broadcast(MinecraftServer server, Component message) {
        Component immutable = message.copy();
        return OrgMenuNativeEffects.admit(server, () -> {
            var recipients = new ArrayList<CompletableFuture<Void>>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                recipients.add(TisCommandContinuations.owned(player, () -> {
                    if (!player.isRemoved()) player.sendSystemMessage(immutable);
                    return null;
                }));
            }
            return CompletableFuture.allOf(recipients.toArray(CompletableFuture[]::new));
        });
    }

    static <T> CompletableFuture<T> file(MinecraftServer server, Supplier<T> action) {
        return OrgMenuNativeEffects.admit(server, () -> {
            var captured = ScarpetRuntime.captureNativeContinuation(action);
            var actual = CompletableFuture.supplyAsync(captured);
            ScarpetNativeWork.record(actual);
            return actual;
        });
    }

    static <T> CompletableFuture<T> global(MinecraftServer server, Supplier<T> action) {
        return OrgMenuNativeEffects.admit(server, () -> {
            var actual = new CompletableFuture<T>();
            ScarpetNativeWork.record(actual);
            var captured = ScarpetRuntime.captureNativeContinuation(() -> TisCommandContinuations.phase(null, action));
            Runnable run = () -> {
                try {
                    captured.get().whenComplete((value, failure) -> {
                        if (failure == null) actual.complete(value);
                        else actual.completeExceptionally(failure);
                    });
                } catch (Throwable failure) {
                    actual.completeExceptionally(failure);
                }
            };
            if (io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread()) run.run();
            else io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(run);
            return actual;
        });
    }

    static <T> CompletableFuture<T> area(net.minecraft.server.level.ServerLevel world, int minX, int minZ, int maxX, int maxZ, Supplier<T> action) {
        return OrgMenuNativeEffects.admit(world.getServer(), () -> {
            var captured = ScarpetRuntime.captureNativeContinuation(() -> TisCommandContinuations.phase(null, action));
            java.util.concurrent.CompletableFuture<java.util.concurrent.CompletableFuture<T>> held = CarpetRegionLease.<java.util.concurrent.CompletableFuture<T>>runLoadedValue(world, minX, minZ, maxX, maxZ, lease -> captured.get());
            var actual = held.thenCompose(value -> value);
            ScarpetNativeWork.record(actual);
            return actual;
        });
    }

    static CompletableFuture<Boolean> teleport(ServerPlayer player, Location destination, Runnable arrived) {
        return OrgMenuNativeEffects.admit(player.level().getServer(), () -> {
            var trip = OrgMenuNativeEffects.run(player, () -> {
                var actual = player.getBukkitEntity().teleportAsync(destination.clone(), PlayerTeleportEvent.TeleportCause.COMMAND);
                ScarpetNativeWork.record(actual);
                return actual;
            }).thenCompose(value -> value);
            return TisCommandContinuations.then(trip, success -> success
                    ? OrgMenuNativeEffects.run(player, () -> {
                arrived.run();
                return true;
            })
                    : CompletableFuture.completedFuture(false));
        });
    }

    /**
     * Group acquisition itself remains a waiting intent until its real owner can borrow the group.
     */
    static <T> CompletableFuture<T> intent(ServerPlayer player, Supplier<T> action) {
        return OrgMenuNativeEffects.admit(player.level().getServer(), () -> {
            var captured = ScarpetRuntime.captureNativeContinuation(() ->
                    TisCommandContinuations.phase(player, action));
            return startIntent(player, captured);
        });
    }

    private static <T> CompletableFuture<T> startIntent(ServerPlayer player, Supplier<CompletableFuture<T>> action) {
        return OrgFakePlayerActions.owned(player, () -> ScarpetPlayerInventoryGate.paused(player)
                ? TisCommandContinuations.then(ScarpetPlayerInventoryGate.whenOpen(player), ignored -> startIntent(player, action))
                : action.get()).thenCompose(value -> value);
    }
}
