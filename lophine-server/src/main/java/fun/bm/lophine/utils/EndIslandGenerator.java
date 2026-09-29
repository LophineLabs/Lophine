package fun.bm.lophine.utils;

import fun.bm.lophine.config.modules.function.OldFeatureConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

public class EndIslandGenerator {
    public static float getDefaultDoffs(float x, float z) {
        if (OldFeatureConfig.shouldGenerateEndRing) {
            long i = (long) x * (long) x + (long) z * (long) z;
            int j = (int) (i << 32 >> 32);
            if (j < 0) {
                return Float.NaN;
            }

            float k = 100.0F - Mth.sqrt(i) * 8;
            return Mth.clamp(k, -100.0F, 80.0F);
        }
        return -100.0F;
    }

    public static void clearOverflowBlocks(final ChunkAccess chunk) {
        if (!OldFeatureConfig.shouldGenerateEndRing) return;
        if (chunk.getHighestFilledSectionIndex() == -1) return;

        ChunkPos chunkPos = chunk.getPos();
        int sectionXMin = chunkPos.x() << 1;
        int sectionXMax = sectionXMin + 1;
        int sectionZMin = chunkPos.z() << 1;
        int sectionZMax = sectionZMin + 1;
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        for (int sectionX = sectionXMin; sectionX <= sectionXMax; sectionX++) {
            for (int sectionZ = sectionZMin; sectionZ <= sectionZMax; sectionZ++) {
                float doffs = fun.bm.lophine.utils.EndIslandGenerator.getDefaultDoffs(sectionX, sectionZ);
                if (doffs != doffs) {
                    int minX = sectionX << 3;
                    int maxX = minX + 7;
                    int minZ = sectionZ << 3;
                    int maxZ = minZ + 7;
                    for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
                        BlockState state = chunk.getBlockState(pos);
                        if (state.is(Blocks.END_STONE)) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    public static boolean[] shouldSetBlock(int sectionXMin, int sectionXMax, int sectionZMin, int sectionZMax) {
        boolean[] inRing = new boolean[(sectionXMax - sectionXMin + 1) * (sectionZMax - sectionZMin + 1)];
        int width = sectionXMax - sectionXMin + 1;
        for (int sectionX = sectionXMin; sectionX <= sectionXMax; sectionX++) {
            for (int sectionZ = sectionZMin; sectionZ <= sectionZMax; sectionZ++) {
                float doffs = fun.bm.lophine.utils.EndIslandGenerator.getDefaultDoffs(sectionX, sectionZ);
                int dSectionX = sectionX - sectionXMin;
                int dSectionZ = sectionZ - sectionZMin;
                inRing[dSectionX + dSectionZ * width] = doffs == doffs;
            }
        }
        return inRing;
    }
}
