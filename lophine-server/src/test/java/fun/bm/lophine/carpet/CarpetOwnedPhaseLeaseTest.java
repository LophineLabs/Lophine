package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetOwnedPhaseLeaseTest {
    @BeforeAll
    static void bootstrap() {
        CarpetLeaseCleanupDrainTest.bootstrap();
    }

    @Test
    void aCompleteOwnedFullNativePhaseExecutesBeforeReturningWithoutTicketOrSchedulerAdmission() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var chunk = mock(LevelChunk.class);
        when(world.getServer()).thenReturn(server);
        when(world.getChunkIfLoaded(0, 0)).thenReturn(chunk);
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, 0, 0, 0, 0)).thenReturn(true);
            var original = Thread.currentThread();
            var result = CarpetRegionLease.runOwnedPhaseValue(world, 0, 0, 0, 0, lease -> {
                assertSame(original, Thread.currentThread());
                assertTrue(lease.ownsAll());
                return 12;
            });
            assertTrue(result.isDone());
            assertEquals(12, result.join());
            assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
            verify(world, never()).moonrise$getChunkTaskScheduler();
        }
    }

    @Test
    void anOwnedPhaseStillRetainsBothReturnedNativeStagesUntilTheirActualEnd() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var chunk = mock(LevelChunk.class);
        when(world.getServer()).thenReturn(server);
        when(world.getChunkIfLoaded(0, 0)).thenReturn(chunk);
        var first = new CompletableFuture<CompletableFuture<Void>>();
        var second = new CompletableFuture<Void>();
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, 0, 0, 0, 0)).thenReturn(true);
            var result = CarpetRegionLease.<CompletableFuture<CompletableFuture<Void>>>runOwnedPhaseValue(world, 0, 0, 0, 0, lease -> first);
            assertSame(first, result.join());
            var idle = ScarpetNativeWork.whenIdle(server);
            assertFalse(idle.isDone());
            first.complete(second);
            assertFalse(idle.isDone());
            second.complete(null);
            idle.join();
            verify(world, never()).moonrise$getChunkTaskScheduler();
        }
    }

    @Test
    void aLoadedOnlyOwnedQueryPreservesUnloadedHolesAndNeverStartsGeneration() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, -1, -1, 1, 1)).thenReturn(true);
            var result = CarpetRegionLease.runOwnedLoadedPhaseValue(world, -1, -1, 1, 1, lease -> "query");
            assertEquals("query", result.join());
            assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
            verify(world, never()).getChunkIfLoaded(anyInt(), anyInt());
            verify(world, never()).moonrise$getChunkTaskScheduler();
        }
    }

    @Test
    void closeRejectsIndependentOwnedPhasesWhileAlreadyAcceptedNativeContinuationCanStillDrain() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var chunk = mock(LevelChunk.class);
        when(world.getServer()).thenReturn(server);
        when(world.getChunkIfLoaded(0, 0)).thenReturn(chunk);
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, 0, 0, 0, 0)).thenReturn(true);
            CarpetRegionLease.beginShutdown(server);
            assertTrue(CarpetRegionLease.runOwnedPhaseValue(world, 0, 0, 0, 0, lease -> fail("Closed independent body")).isCompletedExceptionally());
            ScarpetNativeWork.observeNative(null, () -> {
                assertEquals(3, CarpetRegionLease.runOwnedPhaseValue(world, 0, 0, 0, 0, lease -> 3).join());
                return null;
            }).join();
            assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
        }
    }
}
