package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TisEntitySectionOverflowTest {
    @Test
    void nativeStorageHandlesTheLastUnsignedSectionKeyAndIncludesLongMaxValue() {
        boolean previous = GeneralCompatConfig.entityChunkSectionIndexXOverflowFix;
        try {
            var storage = new EntitySectionStorage<>(Entity.class, key -> Visibility.TICKING);
            int x = (1 << 21) - 1, z = -1;
            long last = SectionPos.asLong(x, -1, z);
            assertEquals(Long.MAX_VALUE, last);
            storage.getOrCreateSection(last);
            GeneralCompatConfig.entityChunkSectionIndexXOverflowFix = true;
            assertArrayEquals(new long[]{Long.MAX_VALUE}, storage.getExistingSectionPositionsInChunk(ChunkPos.pack(x, z)).toArray());
            GeneralCompatConfig.entityChunkSectionIndexXOverflowFix = false;
            assertThrows(IllegalArgumentException.class, () -> storage.getExistingSectionPositionsInChunk(ChunkPos.pack(x, z)).toArray());
        } finally {
            GeneralCompatConfig.entityChunkSectionIndexXOverflowFix = previous;
        }
    }
}
