// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet AMS Addition block loaders, revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;

public final class AmsBlockChunkLoaders {
    private AmsBlockChunkLoaders() {
    }

    public static void note(final ServerLevel level, final BlockPos pos) {
        final String option = GeneralCompatConfig.noteBlockChunkLoader;
        if ("false".equals(option)) {
            return;
        }
        final BlockState above = level.getBlockState(pos.above());
        if ("note_block".equals(option)
                || ("bone_block".equals(option) && above.is(Blocks.BONE_BLOCK))
                || ("wither_skeleton_skull".equals(option) && (above.is(Blocks.WITHER_SKELETON_SKULL) || above.is(Blocks.WITHER_SKELETON_WALL_SKULL)))) {
            add(level, pos, TicketType.AMS_NOTE_BLOCK_LOADER);
        }
    }

    public static void piston(final ServerLevel level, final BlockPos pos, final BlockState state) {
        final String option = GeneralCompatConfig.pistonBlockChunkLoader;
        if ("false".equals(option)) {
            return;
        }
        if (carpet.script.external.ScarpetNativeWork.capture() != null) {
            AmsNativeCommandEffects.receipt(pistonAsync(level, pos, state));
            return;
        }
        final Direction facing = state.getValue(PistonBaseBlock.FACING);
        final BlockPos target = pos.relative(facing);
        final BlockState above = level.getBlockState(pos.above());
        final BlockState below = level.getBlockState(pos.below());
        if (("bone_block".equals(GeneralCompatConfig.pistonBlockChunkLoader) || "all".equals(GeneralCompatConfig.pistonBlockChunkLoader)) && above.is(Blocks.BONE_BLOCK)) {
            add(level, target, TicketType.AMS_PISTON_BLOCK_LOADER);
        }
        if (("bedrock".equals(GeneralCompatConfig.pistonBlockChunkLoader) || "all".equals(GeneralCompatConfig.pistonBlockChunkLoader)) && below.is(Blocks.BEDROCK)) {
            add(level, target, TicketType.AMS_PISTON_BLOCK_LOADER);
        }
    }

    private record PistonRead(BlockPos target, BlockState above, BlockState below) {
    }

    private static java.util.concurrent.CompletableFuture<Void> pistonAsync(final ServerLevel level, final BlockPos pos, final BlockState state) {
        return AmsNativeCommandEffects.nativeReceipt(level.getServer(), () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(level, pos, () ->
                        new PistonRead(pos.relative(state.getValue(PistonBaseBlock.FACING)), level.getBlockState(pos.above()), level.getBlockState(pos.below()))), read ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(level, pos, () -> {
                            if (("bone_block".equals(GeneralCompatConfig.pistonBlockChunkLoader) || "all".equals(GeneralCompatConfig.pistonBlockChunkLoader)) && read.above().is(Blocks.BONE_BLOCK))
                                add(level, read.target(), TicketType.AMS_PISTON_BLOCK_LOADER);
                            return (Void) null;
                        }), ignored -> AmsNativeCommandEffects.world(level, pos, () -> {
                            if (("bedrock".equals(GeneralCompatConfig.pistonBlockChunkLoader) || "all".equals(GeneralCompatConfig.pistonBlockChunkLoader)) && read.below().is(Blocks.BEDROCK))
                                add(level, read.target(), TicketType.AMS_PISTON_BLOCK_LOADER);
                            return (Void) null;
                        }))));
    }

    public static void bell(final ServerLevel level, final BlockPos pos) {
        if (GeneralCompatConfig.bellBlockChunkLoader) {
            add(level, pos, TicketType.AMS_BELL_BLOCK_LOADER);
        }
    }

    private static void add(final ServerLevel level, final BlockPos pos, final TicketType<?> type) {
        if (carpet.script.external.ScarpetNativeWork.capture() != null) {
            AmsNativeCommandEffects.receipt(addAsync(level, pos, type));
            return;
        }
        final int radius = Math.clamp(GeneralCompatConfig.blockChunkLoaderRangeController, 1, 300);
        final ChunkPos center = ChunkPos.containing(pos);
        // Moonrise's propagator accepts source levels up to 62. Tile larger
        // squares so their full/block/entity ticking boundaries stay identical.
        final int maxNativeRadius = 62 - (ChunkLevel.MAX_LEVEL - ChunkLevel.FULL_CHUNK_LEVEL + 1);
        final int tileRadius = Math.min(radius, maxNativeRadius);
        final int[] offsets = tileOffsets(radius, tileRadius);
        for (int dx : offsets) {
            for (int dz : offsets) {
                level.getChunkSource().addTicketWithRadius(type, new ChunkPos(center.x() + dx, center.z() + dz), tileRadius);
            }
        }
        if (GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate) {
            level.resetEmptyTime();
        }
    }

    private static java.util.concurrent.CompletableFuture<Void> addAsync(final ServerLevel level, final BlockPos pos, final TicketType<?> type) {
        return AmsNativeCommandEffects.nativeReceipt(level.getServer(), () -> {
            final int radius = Math.clamp(GeneralCompatConfig.blockChunkLoaderRangeController, 1, 300);
            final ChunkPos center = ChunkPos.containing(pos);
            final int maxNativeRadius = 62 - (ChunkLevel.MAX_LEVEL - ChunkLevel.FULL_CHUNK_LEVEL + 1);
            final int tileRadius = Math.min(radius, maxNativeRadius);
            final int[] offsets = tileOffsets(radius, tileRadius);
            var tiles = new java.util.ArrayList<ChunkPos>();
            for (int dx : offsets) for (int dz : offsets) tiles.add(new ChunkPos(center.x() + dx, center.z() + dz));
            return AmsNativeCommandEffects.then(addTileNext(level, pos, type, tileRadius, tiles.iterator()), ignored ->
                    AmsNativeCommandEffects.world(level, pos, () -> {
                        if (GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate) level.resetEmptyTime();
                        return (Void) null;
                    }));
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> addTileNext(final ServerLevel level, final BlockPos pos, final TicketType<?> type,
                                                                            final int radius, final java.util.Iterator<ChunkPos> tiles) {
        if (!tiles.hasNext()) return java.util.concurrent.CompletableFuture.completedFuture(null);
        final ChunkPos center = tiles.next();
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(level, pos, () -> {
            level.getChunkSource().addTicketWithRadius(type, center, radius);
            return (Void) null;
        }), ignored -> addTileNext(level, pos, type, radius, tiles));
    }

    static int[] tileOffsets(final int radius, final int tileRadius) {
        if (radius <= tileRadius) {
            return new int[]{0};
        }
        if (tileRadius < 2) {
            throw new IllegalArgumentException("The native ticket radius is too small for tiling");
        }
        final ArrayList<Integer> offsets = new ArrayList<>();
        final int end = radius - tileRadius;
        int offset = -end;
        offsets.add(offset);
        while (offset < end) {
            offset = Math.min(end, offset + 2 * tileRadius - 3);
            offsets.add(offset);
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }
}
