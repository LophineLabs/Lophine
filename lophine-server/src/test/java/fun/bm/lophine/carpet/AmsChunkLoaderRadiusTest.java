package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class AmsChunkLoaderRadiusTest {
    @Test
    void tilingPreservesFullBlockAndEntityTickingBoundaries() {
        for (int radius : new int[]{1, 3, 49, 50, 51, 76, 300}) {
            final int tileRadius = Math.min(radius, 50);
            final int[] offsets = AmsBlockChunkLoaders.tileOffsets(radius, tileRadius);
            for (int x = -radius - 2; x <= radius + 2; ++x) {
                for (int z = -radius - 2; z <= radius + 2; ++z) {
                    final int expected = tickClass(33 - radius + Math.max(Math.abs(x), Math.abs(z)));
                    int minimum = Integer.MAX_VALUE;
                    for (int dx : offsets) {
                        for (int dz : offsets) {
                            minimum = Math.min(minimum, 33 - tileRadius + Math.max(Math.abs(x - dx), Math.abs(z - dz)));
                        }
                    }
                    assertEquals(expected, tickClass(minimum), "radius=" + radius + ", x=" + x + ", z=" + z);
                }
            }
        }
    }

    private static int tickClass(final int level) {
        return level <= 31 ? 3 : level <= 32 ? 2 : level <= 33 ? 1 : 0;
    }
}
