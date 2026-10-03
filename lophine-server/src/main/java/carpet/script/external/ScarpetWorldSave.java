// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Source save() queues each real owner snapshot and awaits disk flush only on the VM. */
public final class ScarpetWorldSave {
    public static final ThreadLocal<Boolean> FORCE_SAVE = ThreadLocal.withInitial(() -> false);
    private ScarpetWorldSave() {}
    public static void save(MinecraftServer server) {
        List<ServerLevel> worlds = ScarpetRuntime.atGlobal(server, () -> java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false).toList());
        List<ServerPlayer> players = ScarpetRuntime.atGlobal(server, () -> List.copyOf(server.getPlayerList().getPlayers()));
        List<CompletableFuture<?>> snapshots = new ArrayList<>();
        for (ServerPlayer player : players) snapshots.add(ScarpetRuntime.atEntityFuture(player, () -> { server.getPlayerList().carpetSavePlayerForScarpet(player); return null; }));
        for (ServerLevel world : worlds) {
            var manager = world.moonrise$getChunkTaskScheduler().chunkHolderManager;
            for (var holder : manager.getChunkHolders()) snapshots.add(ScarpetRuntime.atBlockFuture(world, new BlockPos(holder.chunkX << 4, 0, holder.chunkZ << 4), () -> {
                boolean previous = FORCE_SAVE.get(); FORCE_SAVE.set(true);
                try { if (manager.getChunkHolder(holder.chunkX, holder.chunkZ) == holder) holder.save(false); }
                finally { FORCE_SAVE.set(previous); }
                return null;
            }));
        }
        ScarpetRuntime.await(CompletableFuture.allOf(snapshots.toArray(CompletableFuture[]::new)));
        List<CompletableFuture<?>> disk = ScarpetRuntime.atGlobal(server, () -> {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (ServerLevel world : worlds) {
                world.saveLevelData(false);
                pending.add(world.getChunkSource().getDataStorage().carpetScheduledWrites());
            }
            server.saveGlobalData(false);
            pending.add(server.getDataStorage().carpetScheduledWrites());
            return pending;
        });
        ScarpetRuntime.await(CompletableFuture.allOf(disk.toArray(CompletableFuture[]::new)));
        ScarpetRuntime.await(CompletableFuture.runAsync(() -> {
            for (ServerLevel world : worlds) {
                MoonriseRegionFileIO.flush(world);
                try { MoonriseRegionFileIO.flushRegionStorages(world); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        }));
    }
}
