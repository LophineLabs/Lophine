package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real section palette/count/list mutation across both directions of the hot rule change.
 */
public class CarpetDynamicEggTickListTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private LevelChunkSection section() {
        return new LevelChunkSection(new PalettedContainer<BlockState>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null), null);
    }

    @Test
    void disabledEggDoesNotCreateRandomTickWorkAndHotEnableThenDisableRebuildsTheList() {
        boolean before = GeneralCompatConfig.renewableDragonEgg;
        try {
            GeneralCompatConfig.renewableDragonEgg = false;
            var egg = Blocks.DRAGON_EGG.defaultBlockState();
            var section = section();
            assertFalse(egg.isRandomlyTicking());
            section.setBlockState(1, 2, 3, egg);
            section.setBlockState(5, 6, 7, egg);
            assertFalse(section.isRandomlyTickingBlocks());
            assertEquals(0, section.moonrise$getTickingBlockList().size());
            GeneralCompatConfig.renewableDragonEgg = true;
            assertTrue(egg.isRandomlyTicking());
            assertTrue(section.isRandomlyTickingBlocks());
            assertEquals(2, section.moonrise$getTickingBlockList().size());
            GeneralCompatConfig.renewableDragonEgg = false;
            assertFalse(egg.isRandomlyTicking());
            assertEquals(0, section.moonrise$getTickingBlockList().size());
            assertFalse(section.isRandomlyTickingBlocks());
        } finally {
            GeneralCompatConfig.renewableDragonEgg = before;
        }
    }

    @Test
    void mutationAfterEitherRuleChangeKeepsTheCountAndActualCoordinatesAligned() {
        boolean before = GeneralCompatConfig.renewableDragonEgg;
        try {
            GeneralCompatConfig.renewableDragonEgg = false;
            var section = section();
            section.setBlockState(1, 2, 3, Blocks.DRAGON_EGG.defaultBlockState());
            section.setBlockState(9, 10, 11, Blocks.WHEAT.defaultBlockState());
            assertEquals(1, section.moonrise$getTickingBlockList().size());
            GeneralCompatConfig.renewableDragonEgg = true;
            section.setBlockState(1, 2, 3, Blocks.AIR.defaultBlockState());
            section.setBlockState(5, 6, 7, Blocks.DRAGON_EGG.defaultBlockState());
            assertTrue(section.isRandomlyTickingBlocks());
            assertEquals(2, section.moonrise$getTickingBlockList().size());
            GeneralCompatConfig.renewableDragonEgg = false;
            section.setBlockState(5, 6, 7, Blocks.AIR.defaultBlockState());
            assertEquals(1, section.moonrise$getTickingBlockList().size());
            assertEquals((short) (9 | (11 << 4) | (10 << 8)), section.moonrise$getTickingBlockList().getRaw(0));
            section.setBlockState(9, 10, 11, Blocks.AIR.defaultBlockState());
            assertFalse(section.isRandomlyTickingBlocks());
            assertEquals(0, section.moonrise$getTickingBlockList().size());
        } finally {
            GeneralCompatConfig.renewableDragonEgg = before;
        }
    }
}
