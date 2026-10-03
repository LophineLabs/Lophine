package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class CarpetFastRedstoneSourceTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object node(BlockState state, BlockPos pos) throws Exception {
        var type = Class.forName("fun.bm.lophine.carpet.CarpetRedstoneWireTurbo$UpdateNode");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); var result = constructor.newInstance();
        field(result, "currentState", state); field(result, "self", pos);
        var kind = Class.forName("fun.bm.lophine.carpet.CarpetRedstoneWireTurbo$UpdateNode$Type");
        field(result, "type", Enum.valueOf((Class) kind, state.is(Blocks.REDSTONE_WIRE) ? "REDSTONE" : "OTHER"));
        return result;
    }
    private static BlockState calculate(CarpetRedstoneWireTurbo turbo, ServerLevel world, Object node) throws Exception {
        var method = CarpetRedstoneWireTurbo.class.getDeclaredMethod("calculateCurrentChanges", Level.class, node.getClass());
        method.setAccessible(true); return (BlockState) method.invoke(turbo, world, node);
    }

    @Test void fastRuleReallySelectsCarpetTurboWithNullSourceEvenWhenPaperUsesAlternateCurrent() throws Exception {
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fastRedstoneDust;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fastRedstoneDust = true;
            var world = mock(ServerLevel.class); var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);
            var turbo = mock(CarpetRedstoneWireTurbo.class);
            var field = io.papermc.paper.threadedregions.RegionizedWorldData.class.getField("carpetWireTurbo"); field.setAccessible(true); field.set(data, turbo);
            when(world.getCurrentWorldData()).thenReturn(data); when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
            var config = mock(io.papermc.paper.configuration.WorldConfiguration.class);
            var misc = mock(io.papermc.paper.configuration.WorldConfiguration.Misc.class);
            var miscField = io.papermc.paper.configuration.WorldConfiguration.class.getField("misc"); miscField.setAccessible(true); miscField.set(config, misc);
            misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
            when(world.paperConfig()).thenReturn(config);
            var pos = new BlockPos(-23, 73, 37); var state = Blocks.REDSTONE_WIRE.defaultBlockState();
            var method = RedstoneWireBlock.class.getDeclaredMethod("onPlace", BlockState.class, Level.class, BlockPos.class, BlockState.class, boolean.class);
            method.setAccessible(true); method.invoke(Blocks.REDSTONE_WIRE, state, world, pos, Blocks.AIR.defaultBlockState(), false);
            verify(turbo).updateSurroundingRedstone(world, pos, state, null); verify(world, never()).getWireHandler();
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fastRedstoneDust = old; }
    }

    @Test void cachedPowerUsesOriginalNeighborStrengthAndPreservesActualBukkitModification() throws Exception {
        var world = mock(ServerLevel.class); var pos = new BlockPos(-23, 73, 37);
        var state = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.POWER, 5);
        var target = node(state, pos); var type = target.getClass(); var neighbors = Array.newInstance(type, 24);
        for (int index = 0; index < 24; index++) Array.set(neighbors, index, node(Blocks.AIR.defaultBlockState(), pos.offset(index, 1, 0)));
        Array.set(neighbors, 4, node(state.setValue(RedstoneWireBlock.POWER, 14), pos.east())); field(target, "neighbor_nodes", neighbors);
        var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class); data.shouldSignal = true;
        when(world.getBestNeighborSignal(pos)).thenAnswer(call -> { assertFalse(data.shouldSignal); return 0; });
        when(world.getBlockState(pos)).thenReturn(state);
        try (var ticks = mockStatic(io.papermc.paper.threadedregions.TickRegionScheduler.class); var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            ticks.when(io.papermc.paper.threadedregions.TickRegionScheduler::getCurrentRegionizedWorldData).thenReturn(data);
            var event = mock(org.bukkit.event.block.BlockRedstoneEvent.class); when(event.getNewCurrent()).thenReturn(11);
            events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(world, pos, 5, 13)).thenReturn(event);
            var result = calculate(new CarpetRedstoneWireTurbo((RedstoneWireBlock) Blocks.REDSTONE_WIRE), world, target);
            assertEquals(11, result.getValue(RedstoneWireBlock.POWER)); assertTrue(data.shouldSignal);
            verify(world).setBlock(pos, result, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
        }
    }

    @Test void cachedSourceCheckDoesNotRestoreWireAlreadyReplacedByAnotherNativeUpdate() throws Exception {
        var world = mock(ServerLevel.class); var pos = BlockPos.ZERO; var state = Blocks.REDSTONE_WIRE.defaultBlockState(); var target = node(state, pos);
        when(world.getBestNeighborSignal(pos)).thenReturn(15); when(world.getBlockState(pos)).thenReturn(Blocks.AIR.defaultBlockState());
        var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);
        try (var ticks = mockStatic(io.papermc.paper.threadedregions.TickRegionScheduler.class); var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            ticks.when(io.papermc.paper.threadedregions.TickRegionScheduler::getCurrentRegionizedWorldData).thenReturn(data);
            var event = mock(org.bukkit.event.block.BlockRedstoneEvent.class); when(event.getNewCurrent()).thenReturn(15);
            events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(world, pos, 0, 15)).thenReturn(event);
            assertEquals(15, calculate(new CarpetRedstoneWireTurbo((RedstoneWireBlock) Blocks.REDSTONE_WIRE), world, target).getValue(RedstoneWireBlock.POWER));
            verify(world, never()).setBlock(any(), any(), anyInt());
        }
    }

    @Test void genuineSignalFailureRestoresThisRegionsWireSignalFlagAndRetainsItsCause() throws Exception {
        var world = mock(ServerLevel.class); var pos = BlockPos.ZERO; var failure = new IllegalStateException("actual signal failure");
        when(world.getBestNeighborSignal(pos)).thenThrow(failure); var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class); data.shouldSignal = true;
        try (var ticks = mockStatic(io.papermc.paper.threadedregions.TickRegionScheduler.class)) {
            ticks.when(io.papermc.paper.threadedregions.TickRegionScheduler::getCurrentRegionizedWorldData).thenReturn(data);
            var wrapped = assertThrows(InvocationTargetException.class, () -> calculate(new CarpetRedstoneWireTurbo((RedstoneWireBlock) Blocks.REDSTONE_WIRE), world, node(Blocks.REDSTONE_WIRE.defaultBlockState(), pos)));
            assertSame(failure, wrapped.getCause()); assertTrue(data.shouldSignal);
        }
    }

    @Test void manualShapeUpdatesFollowThePinnedNativeShapeOrder() throws Exception {
        var world = mock(ServerLevel.class); var pos = new BlockPos(-23, 73, 37); var visited = new ArrayList<BlockPos>();
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(call -> { visited.add(call.getArgument(0)); return Blocks.STONE.defaultBlockState(); });
        when(world.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(137));
        new CarpetRedstoneWireTurbo((RedstoneWireBlock) Blocks.REDSTONE_WIRE).updateNeighborShapes(world, pos, Blocks.REDSTONE_WIRE.defaultBlockState());
        var order = net.minecraft.world.level.block.state.BlockBehaviour.class.getDeclaredField("UPDATE_SHAPE_ORDER"); order.setAccessible(true);
        assertEquals(Arrays.stream((Direction[]) order.get(null)).map(pos::relative).toList(), visited);
    }
}
