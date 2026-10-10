package carpet.script.external;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

public class TisLazyRedstoneSemanticsTest {
    @BeforeAll
    static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
    }

    boolean priorLazy, priorIgnore, priorNoUpdate;

    @BeforeEach
    void save() {
        priorLazy = GeneralCompatConfig.keepMobInLazyChunks;
        priorIgnore = GeneralCompatConfig.tntIgnoreRedstoneSignal;
        priorNoUpdate = GeneralCompatConfig.tntDoNotUpdate;
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.keepMobInLazyChunks = priorLazy;
        GeneralCompatConfig.tntIgnoreRedstoneSignal = priorIgnore;
        GeneralCompatConfig.tntDoNotUpdate = priorNoUpdate;
    }

    private static void field(Object actual, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(actual, value);
    }

    @Test
    void sourceInertLazyApiCannotRelocateNativeDespawnIntoTheActualAiPass() throws Exception {
        var mob = mock(Mob.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        doReturn(world).when(mob).level();
        doReturn(0).when(mob).getId();
        mob.aware = true;
        var sensing = mock(net.minecraft.world.entity.ai.sensing.Sensing.class);
        var targets = mock(GoalSelector.class);
        var goals = mock(GoalSelector.class);
        var navigation = mock(net.minecraft.world.entity.ai.navigation.PathNavigation.class);
        var move = mock(net.minecraft.world.entity.ai.control.MoveControl.class);
        var look = mock(net.minecraft.world.entity.ai.control.LookControl.class);
        var jump = mock(net.minecraft.world.entity.ai.control.JumpControl.class);
        field(mob, Mob.class, "sensing", sensing);
        field(mob, Mob.class, "targetSelector", targets);
        field(mob, Mob.class, "goalSelector", goals);
        field(mob, Mob.class, "navigation", navigation);
        field(mob, Mob.class, "moveControl", move);
        field(mob, Mob.class, "lookControl", look);
        field(mob, Mob.class, "jumpControl", jump);
        Method ai = Mob.class.getDeclaredMethod("serverAiStep");
        ai.setAccessible(true);
        GeneralCompatConfig.keepMobInLazyChunks = true;
        ai.invoke(mob);
        GeneralCompatConfig.keepMobInLazyChunks = false;
        ai.invoke(mob);
        verify(mob, never()).checkDespawn();
        verify(sensing, times(2)).tick();
        verify(navigation, times(2)).tick();
        verify(move, times(2)).tick();
        verify(look, times(2)).tick();
        verify(jump, times(2)).tick();
    }

    @Test
    void nativeNeighborSignalGetterRunsBeforeTheTisFalseMaskAndNoPrimeTailRuns() throws Exception {
        GeneralCompatConfig.tntIgnoreRedstoneSignal = true;
        var world = mock(ServerLevel.class);
        when(world.hasNeighborSignal(BlockPos.ZERO)).thenReturn(true);
        Method changed = TntBlock.class.getDeclaredMethod("neighborChanged", BlockState.class, Level.class, BlockPos.class, Block.class, net.minecraft.world.level.redstone.Orientation.class, boolean.class);
        changed.setAccessible(true);
        changed.invoke(Blocks.TNT, Blocks.TNT.defaultBlockState(), world, BlockPos.ZERO, Blocks.STONE, null, false);
        verify(world).hasNeighborSignal(BlockPos.ZERO);
        verify(world, never()).removeBlock(any(), anyBoolean());
    }

    @Test
    void nativePlacementKeepsSignalGetterBeforeMaskAndPreservesIndependentNoUpdateHeadGuard() throws Exception {
        GeneralCompatConfig.tntIgnoreRedstoneSignal = true;
        GeneralCompatConfig.tntDoNotUpdate = false;
        var world = mock(ServerLevel.class);
        when(world.hasNeighborSignal(BlockPos.ZERO)).thenReturn(true);
        Method placed = TntBlock.class.getDeclaredMethod("onPlace", BlockState.class, Level.class, BlockPos.class, BlockState.class, boolean.class);
        placed.setAccessible(true);
        placed.invoke(Blocks.TNT, Blocks.TNT.defaultBlockState(), world, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), false);
        verify(world).hasNeighborSignal(BlockPos.ZERO);
        GeneralCompatConfig.tntDoNotUpdate = true;
        placed.invoke(Blocks.TNT, Blocks.TNT.defaultBlockState(), world, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), false);
        verify(world, times(1)).hasNeighborSignal(BlockPos.ZERO);
    }
}
