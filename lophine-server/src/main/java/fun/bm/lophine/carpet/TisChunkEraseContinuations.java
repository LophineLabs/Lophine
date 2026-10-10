// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.CompletableFuture;

/**
 * The original erase order, with each actual removal and its children finished before the next native phase.
 */
public final class TisChunkEraseContinuations {
    private TisChunkEraseContinuations() {
    }

    public static CompletableFuture<int[]> erase(LevelChunk chunk, ServerLevel world) {
        var actual = new CompletableFuture<int[]>();
        ScarpetNativeWork.record(actual);
        var caller = ScarpetNativeWork.trackNative(world.getServer(), actual);
        var observed = ScarpetNativeWork.observeNative(null, () -> {
            var entities = chunk.carpetEraseEntitySnapshot();
            CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
            for (var entity : entities)
                sequence = TisCommandContinuations.then(sequence, ignored ->
                        TisCommandContinuations.owned(entity, () -> {
                            entity.discard();
                            return null;
                        }));
            BlockPos center = new BlockPos(chunk.getPos().getMinBlockX(), 0, chunk.getPos().getMinBlockZ());
            var blocks = TisCommandContinuations.then(sequence, ignored -> TisCommandContinuations.owned(world, center,
                    chunk::carpetEraseBlockEntitySnapshot));
            var remainder = TisCommandContinuations.then(blocks, positions -> {
                CompletableFuture<Void> removals = CompletableFuture.completedFuture(null);
                for (var position : positions)
                    removals = TisCommandContinuations.then(removals, ignored ->
                            TisCommandContinuations.owned(world, position, () -> {
                                world.removeBlockEntity(position);
                                return null;
                            }));
                return TisCommandContinuations.then(removals, ignored -> TisCommandContinuations.owned(world, center,
                        () -> chunk.carpetEraseContentsAfterRemovals(entities.size(), positions.size())));
            });
            ScarpetNativeWork.record(remainder);
            return remainder;
        });
        ScarpetNativeWork.trackNative(world.getServer(), observed);
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value).whenComplete((value, failure) -> {
            if (failure == null) actual.complete(value);
            else actual.completeExceptionally(failure);
        });
        return caller;
    }
}
