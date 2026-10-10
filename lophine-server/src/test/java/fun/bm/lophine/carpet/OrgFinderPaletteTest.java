package fun.bm.lophine.carpet;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgFinderPaletteTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void nativePartialPropertyPredicateDoesNotSkipAMatchingSection() throws Exception {
        var predicate = BlockPredicateArgument.parse(BuiltInRegistries.BLOCK, new StringReader("oak_log[axis=x]"));
        var palette = OrgFinderPalette.of(predicate);
        assertTrue(palette.test(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
        assertFalse(palette.test(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)));
        assertFalse(palette.test(Blocks.BIRCH_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
        var anyAxis = OrgFinderPalette.of(BlockPredicateArgument.parse(BuiltInRegistries.BLOCK, new StringReader("oak_log")));
        assertTrue(anyAxis.test(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z)));
    }

    @Test
    void nbtFilteringIsLeftToTheOwnedNativePredicate() throws Exception {
        var predicate = BlockPredicateArgument.parse(BuiltInRegistries.BLOCK, new StringReader("chest{CustomName:'test'}"));
        assertTrue(OrgFinderPalette.of(predicate).test(Blocks.CHEST.defaultBlockState()));
        var world = mock(net.minecraft.world.level.LevelReader.class);
        when(world.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CHEST.defaultBlockState());
        assertFalse(predicate.test(new BlockInWorld(world, BlockPos.ZERO, true)), "A matching palette must not invent the missing block entity/NBT");
    }
}
