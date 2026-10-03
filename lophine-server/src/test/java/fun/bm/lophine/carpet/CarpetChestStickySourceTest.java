package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.*;

class CarpetChestStickySourceTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    private void verifyPair(boolean enabled, Block chest, ChestType firstType, ChestType secondType, boolean expected) {
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities = enabled;
            var world = mock(ServerLevel.class); var pos = new BlockPos(-23, 73, 37); var front = pos.north(); var paired = front.east();
            var first = chest.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, firstType);
            var second = chest.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, secondType);
            var blocks = Map.of(front, first, paired, second);
            when(world.getBlockState(any(BlockPos.class))).thenAnswer(call -> blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
            try (var pistons = mockStatic(PistonBaseBlock.class)) {
                pistons.when(() -> PistonBaseBlock.isPushable(any(BlockState.class), eq(world), any(BlockPos.class), any(Direction.class), anyBoolean(), any(Direction.class))).thenReturn(true);
                var resolver = new PistonStructureResolver(world, pos, Direction.NORTH, true);
                assertTrue(resolver.resolve()); assertTrue(resolver.getToPush().contains(front)); assertEquals(expected, resolver.getToPush().contains(paired));
            }
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities = old; }
    }
    @Test void nativeResolverMovesTheConnectedDoubleChestTogether() { verifyPair(true, Blocks.CHEST, ChestType.LEFT, ChestType.RIGHT, true); }
    @Test void trappedDoubleChestsKeepTheSameNativeConnectionBehavior() { verifyPair(true, Blocks.TRAPPED_CHEST, ChestType.LEFT, ChestType.RIGHT, true); }
    @Test void disabledRuleDoesNotAttachTheOtherChest() { verifyPair(false, Blocks.CHEST, ChestType.LEFT, ChestType.RIGHT, false); }
    @Test void adjacentSingleChestsDoNotBecomeAnAccidentalStickyPair() { verifyPair(true, Blocks.CHEST, ChestType.SINGLE, ChestType.SINGLE, false); }
}
