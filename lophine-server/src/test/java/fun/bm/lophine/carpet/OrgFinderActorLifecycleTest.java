package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class OrgFinderActorLifecycleTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void aStopWaitsTheAlreadyRunningNativeSliceBeforePublishingCancellation() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            var player = fixture.viewer.player();
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            var world = player.level();
            fixture.owner.set(player);
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(fixture.server);
            var child = new CompletableFuture<Void>();
            when(world.getChunkIfLoaded(0, 0)).thenAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            });
            leases.when(() -> CarpetRegionLease.runLoadedValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)))
                    .thenAnswer(call -> CompletableFuture.completedFuture(((Function<?, ?>) call.getArgument(5)).apply(null)));
            var actual = new AtomicReference<CompletableFuture<Void>>();
            var root = ScarpetNativeWork.observeNative(null, () -> {
                actual.set(OrgFinderService.start(source, player, new OrgFinderBounds(0, 0, 0, 0, 0, 0), OrgFinderService.Kind.BLOCK, block -> false, null, null));
                return true;
            });
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(actual.get().isDone());
            assertFalse(root.isDone());
            assertFalse(idle.isDone());
            assertTrue(OrgFinderService.stop(player));
            assertFalse(actual.get().isDone());
            child.complete(null);
            assertFalse(actual.get().isDone());
            fixture.drain(fixture.viewer);
            assertTrue(actual.get().isCompletedExceptionally());
            assertTrue(root.isCompletedExceptionally());
            assertTrue(idle.isDone());
            assertFalse(OrgFinderService.stop(player));
        }
    }

    @Test
    void theRealOutputOwnerPhaseAndItsNativeChildrenRemainTrackedAfterCallerCancellation() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            var player = fixture.viewer.player();
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            var world = player.level();
            fixture.owner.set(player);
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(fixture.server);
            var outputChild = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(outputChild);
                return null;
            }).when(player).sendSystemMessage(any(net.minecraft.network.chat.Component.class));
            leases.when(() -> CarpetRegionLease.runLoadedValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)))
                    .thenAnswer(call -> CompletableFuture.completedFuture(((Function<?, ?>) call.getArgument(5)).apply(null)));
            var actual = new AtomicReference<CompletableFuture<Void>>();
            var root = ScarpetNativeWork.observeNative(null, () -> {
                actual.set(OrgFinderService.start(source, player, new OrgFinderBounds(0, 0, 0, 0, 0, 0), OrgFinderService.Kind.BLOCK, block -> false, null, null));
                return true;
            });
            var caller = actual.get().copy();
            assertTrue(caller.cancel(false));
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(actual.get().isDone());
            fixture.drain(fixture.viewer);
            assertFalse(actual.get().isDone());
            assertFalse(root.isDone());
            assertFalse(idle.isDone());
            outputChild.complete(null);
            actual.get().join();
            assertTrue(root.join());
            assertTrue(idle.isDone());
            assertTrue(caller.isCancelled());
        }
    }
}
