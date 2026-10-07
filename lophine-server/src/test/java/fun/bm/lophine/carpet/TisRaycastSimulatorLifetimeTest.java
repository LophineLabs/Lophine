package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TisRaycastSimulatorLifetimeTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static Map<ChunkPos, List<?>> plan(BlockState state) throws Exception {
        Class<?> placement = Class.forName(TisRaycastSimulator.class.getName() + "$Placement");
        var constructor = placement.getDeclaredConstructor(BlockPos.class, BlockState.class, boolean.class);
        constructor.setAccessible(true);
        return Map.of(new ChunkPos(0, 0), List.of(constructor.newInstance(BlockPos.ZERO, state, false)));
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> dispatch(CommandSourceStack source, Map<ChunkPos, List<?>> plan) throws Exception {
        var method = TisRaycastSimulator.class.getDeclaredMethod("dispatch", CommandSourceStack.class, Map.class);
        method.setAccessible(true);
        try { return (CompletableFuture<Void>) method.invoke(null, source, plan); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    @Test void aSimulatorBatchUsesTheChunkLeaseAndRetainsNativeDrainThroughActualBlockChildren() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var source = mock(CommandSourceStack.class);
        var state = mock(BlockState.class);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(world);
        when(world.getServer()).thenReturn(server);
        var child = new CompletableFuture<Void>();
        when(world.setBlockAndUpdate(BlockPos.ZERO, state)).thenAnswer(call -> { ScarpetNativeWork.record(child); return true; });
        try (var ticks = mockStatic(TickThread.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            leases.when(() -> CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)))
                    .thenAnswer(call -> CompletableFuture.completedFuture(((Function<?, ?>) call.getArgument(5)).apply(null)));
            var actual = dispatch(source, plan(state));
            leases.verify(() -> CarpetRegionLease.runValue(eq(world), eq(0), eq(0), eq(0), eq(0), any(Function.class)));
            assertFalse(actual.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            actual.cancel(false);
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            child.complete(null);
            ScarpetNativeWork.whenIdle(server).join();
            verify(world).setBlockAndUpdate(BlockPos.ZERO, state);
        }
    }

    @Test void shutdownRejectsNewSimulatorBatchesBeforeAnyChunkOrBlockMutation() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(world);
        ScarpetNativeWork.beginDrain(server);
        assertThrows(IllegalStateException.class, () -> dispatch(source, plan(mock(BlockState.class))));
        verifyNoInteractions(world);
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
    }
}
