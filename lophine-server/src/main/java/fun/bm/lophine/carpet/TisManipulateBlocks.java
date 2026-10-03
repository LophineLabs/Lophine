// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class TisManipulateBlocks {
    private TisManipulateBlocks() { }

    static LiteralArgumentBuilder<CommandSourceStack> tree() {
        var execute = literal("execute").then(literal("block_event").then(argument("type", IntegerArgumentType.integer())
            .then(argument("data", IntegerArgumentType.integer()).executes(c -> run(c, "block_event")))));
        for (String name : List.of("tile_tick", "random_tick", "precipitation_tick")) execute.then(literal(name).executes(c -> run(c, name)));
        var emit = literal("emit");
        for (String name : List.of("block_update", "state_update", "light_update")) emit.then(literal(name).executes(c -> run(c, name)));
        return literal("block").then(argument("from", BlockPosArgument.blockPos())
            .then(argument("to", BlockPosArgument.blockPos()).then(execute).then(emit)));
    }

    private static int run(CommandContext<CommandSourceStack> context, String action) {
        CommandSourceStack source = context.getSource();
        BlockPos from = BlockPosArgument.getBlockPos(context, "from"), to = BlockPosArgument.getBlockPos(context, "to");
        int type = "block_event".equals(action) ? IntegerArgumentType.getInteger(context, "type") : 0;
        int data = "block_event".equals(action) ? IntegerArgumentType.getInteger(context, "data") : 0;
        long x = Math.abs((long) from.getX() - to.getX()) + 1L;
        long y = Math.abs((long) from.getY() - to.getY()) + 1L;
        long z = Math.abs((long) from.getZ() - to.getZ()) + 1L;
        long limit = Math.max(1, GeneralCompatConfig.manipulateBlockLimit);
        if (x > limit || y > limit / x || z > limit / (x * y)) {
            TisManipulateCommand.feedback(source, "Block volume exceeds manipulateBlockLimit=" + limit);
            return 0;
        }
        if (!net.minecraft.world.level.Level.isInSpawnableBounds(from) || !net.minecraft.world.level.Level.isInSpawnableBounds(to)) {
            TisManipulateCommand.feedback(source, "Position is outside world bounds");
            return 0;
        }
        BoundingBox bounds = BoundingBox.fromCorners(from, to);
        return chunks(source, bounds, action, (level, pos) -> apply(level, pos, action, type, data));
    }

    @FunctionalInterface private interface BlockOperation { void apply(ServerLevel level, BlockPos pos); }

    private static int chunks(CommandSourceStack source, BoundingBox bounds, String name, BlockOperation action) {
        ServerLevel level = source.getLevel();
        return TisCommandContinuations.complete(source, bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan(), () ->
            TisCommandContinuations.area(source, level, (bounds.minX() - 2) >> 4, (bounds.minZ() - 2) >> 4,
                (bounds.maxX() + 2) >> 4, (bounds.maxZ() + 2) >> 4, () -> {
                CompletableFuture<Integer> sequence = CompletableFuture.completedFuture(0);
                for (BlockPos mutable : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                    BlockPos pos = mutable.immutable();
                    sequence = TisCommandContinuations.then(sequence, count -> applyAsync(level, pos, name,
                        action).thenApply(ignored -> count + 1));
                }
                return sequence;
            }), name + " blocks", () -> { });
    }

    static int clearTicks(CommandSourceStack source, BoundingBox bounds) {
        ServerLevel level = source.getLevel();
        return TisCommandContinuations.complete(source, 1, () -> TisCommandContinuations.area(source, level,
            bounds.minX() >> 4, bounds.minZ() >> 4, bounds.maxX() >> 4, bounds.maxZ() >> 4, () -> TisCommandContinuations.phase(null, () -> {
                int before = level.getBlockTicks().count() + level.getFluidTicks().count();
                level.getBlockTicks().clearArea(bounds); level.getFluidTicks().clearArea(bounds);
                return before - level.getBlockTicks().count() - level.getFluidTicks().count();
            })), "Removed scheduled block/fluid ticks", () -> { });
    }

    private static CompletableFuture<Void> applyAsync(ServerLevel level, BlockPos pos, String name, BlockOperation action) {
        return TisCommandContinuations.owned(level, pos, () -> { action.apply(level, pos); return null; });
    }

    private static void apply(ServerLevel level, BlockPos pos, String action, int type, int data) {
        var state = level.getBlockState(pos);
        switch (action) {
            case "block_event" -> {
                var event = TisCommandContinuations.phase(null, () -> { state.triggerEvent(level, pos, type, data); return null; });
                var packets = TisCommandContinuations.then(event, ignored -> TisCommandContinuations.owned(level, pos, () -> {
                    Vec3Position.broadcast(level, pos, new ClientboundBlockEventPacket(pos, state.getBlock(), type, data)); return null;
                }));
                carpet.script.external.ScarpetNativeWork.record(packets);
            }
            case "tile_tick" -> {
                var block = TisCommandContinuations.phase(null, () -> { state.tick(level, pos, level.getRandom()); return null; });
                var fluid = TisCommandContinuations.then(block, ignored -> TisCommandContinuations.owned(level, pos, () -> {
                    level.getFluidState(pos).tick(level, pos, state); return null;
                }));
                carpet.script.external.ScarpetNativeWork.record(fluid);
            }
            case "random_tick" -> {
                var block = TisCommandContinuations.phase(null, () -> { state.randomTick(level, pos, level.getRandom()); return null; });
                var fluid = TisCommandContinuations.then(block, ignored -> TisCommandContinuations.owned(level, pos, () -> {
                    level.getFluidState(pos).randomTick(level, pos, level.getRandom()); return null;
                }));
                carpet.script.external.ScarpetNativeWork.record(fluid);
            }
            case "precipitation_tick" -> {
                Biome biome = level.getBiome(pos.above()).value();
                Biome.Precipitation precipitation = biome.getPrecipitationAt(pos, level.getSeaLevel());
                if (precipitation != Biome.Precipitation.NONE) state.getBlock().handlePrecipitation(state, level, pos, precipitation);
            }
            case "block_update" -> {
                var neighbours = TisCommandContinuations.phase(null, () -> { level.updateNeighborsAt(pos, state.getBlock()); return null; });
                var output = TisCommandContinuations.then(neighbours, ignored -> TisCommandContinuations.owned(level, pos, () -> {
                    if (state.hasAnalogOutputSignal()) level.updateNeighbourForOutputSignal(pos, state.getBlock()); return null;
                }));
                carpet.script.external.ScarpetNativeWork.record(output);
            }
            case "state_update" -> {
                var direct = TisCommandContinuations.phase(null, () -> { state.updateNeighbourShapes(level, pos, 2, 512); return null; });
                var indirect = TisCommandContinuations.then(direct, ignored -> TisCommandContinuations.owned(level, pos, () -> {
                    state.updateIndirectNeighbourShapes(level, pos, 2, 512); return null;
                }));
                carpet.script.external.ScarpetNativeWork.record(indirect);
            }
            case "light_update" -> level.getChunkSource().getLightEngine().checkBlock(pos);
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static final class Vec3Position {
        static void broadcast(ServerLevel level, BlockPos pos, ClientboundBlockEventPacket packet) {
            double radius = GeneralCompatConfig.blockEventPacketRange; double radiusSquared = radius * radius;
            var audience = new CompletableFuture<List<net.minecraft.server.level.ServerPlayer>>();
            carpet.script.external.ScarpetNativeWork.record(audience);
            var capture = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() -> {
                audience.complete(List.copyOf(level.getServer().getPlayerList().getPlayers())); return null;
            });
            RegionizedServer.getInstance().addTask(() -> { try { capture.get(); } catch (Throwable failure) { audience.completeExceptionally(failure); } });
            var sent = TisCommandContinuations.then(audience, players -> {
                List<CompletableFuture<Void>> receipts = new ArrayList<>();
                for (var player : players) receipts.add(TisCommandContinuations.owned(player, () -> {
                    if (!player.isRemoved() && player.level() == level && player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < radiusSquared)
                        player.connection.send(packet);
                    return null;
                }));
                return CompletableFuture.allOf(receipts.toArray(CompletableFuture[]::new));
            });
            carpet.script.external.ScarpetNativeWork.record(sent);
        }
    }
}
