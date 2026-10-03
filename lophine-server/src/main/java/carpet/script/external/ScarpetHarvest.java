// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.CarpetContext;
import carpet.script.argument.BlockArgument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** VM entry: the real destruction result arrives after cancellable Native phases finish. */
public final class ScarpetHarvest {
    private record Location(ServerLevel world, BlockPos position) {}
    private ScarpetHarvest() {}
    public static Value harvest(CarpetContext context, List<Value> args) {
        if (args.size() < 2) throw new InternalExpressionException("'harvest' takes at least 2 parameters: entity and block, or position, to harvest");
        List<Value> captured = ActorFunctions.snapshotArguments(args);
        ServerPlayer player = EntityValue.getPlayerByValue(context.server(), captured.getFirst());
        if (player == null) return Value.FALSE;
        BlockArgument locator = BlockArgument.findIn(context, captured, 1);
        BlockPos where = locator.block.getPos();
        var state = locator.block.getBlockState(); // owner read; may be in a different dimension from the player upstream
        Block block = state.getBlock();
        for (int attempt = 0; attempt < 8; attempt++) {
            Location origin = ScarpetRuntime.atEntity(player, () -> new Location(player.level(), player.blockPosition().immutable()));
            int minX = Math.min(origin.position().getX(), where.getX() - 128) >> 4, minZ = Math.min(origin.position().getZ(), where.getZ() - 128) >> 4;
            int maxX = Math.max(origin.position().getX(), where.getX() + 128) >> 4, maxZ = Math.max(origin.position().getZ(), where.getZ() + 128) >> 4;
            CompletableFuture<Boolean> destroyed = ScarpetRuntime.withArea(origin.world(), minX, minZ, maxX, maxZ, () -> {
                if (!TickThread.isTickThreadFor(player) || player.isRemoved() || player.level() != origin.world()) return null;
                if ((block == Blocks.BEDROCK || block == Blocks.BARRIER) && player.gameMode.isSurvival()) return CompletableFuture.completedFuture(false);
                return ScarpetNativeContinuations.destroyBlockAsync(player.gameMode, where);
            });
            if (destroyed == null) continue;
            boolean success = ScarpetRuntime.await(destroyed);
            if (success) ScarpetRuntime.atBlock(context.level(), where, () -> { context.level().levelEvent(null, 2001, where, Block.getId(state)); return null; });
            return BooleanValue.of(success);
        }
        throw new InternalExpressionException("Player kept changing regions during harvest");
    }
}
