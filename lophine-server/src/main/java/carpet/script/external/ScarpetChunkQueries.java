// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Observations consume owner-published immutable status; they never acquire a FULL chunk ticket.
 */
public final class ScarpetChunkQueries {
    private ScarpetChunkQueries() {
    }

    private static NewChunkHolder holder(ServerLevel world, int x, int z) {
        return world.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(x, z);
    }

    public static boolean loaded(ServerLevel world, BlockPos pos) {
        return world.getChunkIfLoaded(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    public static FullChunkStatus fullStatus(ServerLevel world, BlockPos pos) {
        NewChunkHolder holder = holder(world, pos.getX() >> 4, pos.getZ() >> 4);
        return holder == null || !loaded(world, pos) ? FullChunkStatus.INACCESSIBLE : holder.carpetFullStatusSnapshot();
    }

    public static boolean generatedInMemory(ServerLevel world, ChunkPos pos) {
        NewChunkHolder holder = holder(world, pos.x(), pos.z());
        NewChunkHolder.ChunkCompletion completion = holder == null ? null : holder.getLastChunkCompletion();
        return completion != null && completion.genStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS);
    }

    public static ChunkStatus generationStatus(ServerLevel world, BlockPos pos, boolean loadEmpty) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        NewChunkHolder holder = holder(world, x, z);
        ChunkStatus current = holder == null ? null : holder.carpetPersistedStatusSnapshot();
        if (current != null || !loadEmpty) return current;
        CompletableFuture<ChunkStatus> result = new CompletableFuture<>();
        world.moonrise$loadChunksAsync(x, x, z, z, ChunkStatus.EMPTY, Priority.NORMAL, chunks -> {
            NewChunkHolder loaded = holder(world, x, z);
            result.complete(loaded == null ? null : loaded.carpetPersistedStatusSnapshot());
        });
        return ScarpetRuntime.await(result);
    }
}
