package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetLeaseCleanupDrainTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // The acquisition worker must not perform this JVM-wide class initialization:
        // PluginLogger requires Bukkit's server, and static mocks apply only to this thread.
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            var craft = mock(CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            assertNotNull(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);
        }
    }

    @Test void nativeShutdownWaitsTheRealLeaseTicketReleaseAfterItsActorAndReturnedStageFinish() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var scheduler = mock(ChunkTaskScheduler.class);
        var manager = mock(ChunkHolderManager.class);
        var field = ChunkTaskScheduler.class.getField("chunkHolderManager");
        field.setAccessible(true);
        field.set(scheduler, manager);
        when(world.getServer()).thenReturn(server);
        when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
        var loaded = mock(LevelChunk.class);
        when(world.getChunkIfLoaded(0, 0)).thenReturn(loaded);
        var bukkitWorld = mock(CraftWorld.class);
        when(world.getWorld()).thenReturn(bukkitWorld);
        server.server = mock(CraftServer.class);
        var regions = mock(RegionScheduler.class);
        when(server.server.getRegionScheduler()).thenReturn(regions);
        var queued = new LinkedBlockingQueue<Runnable>();
        doAnswer(call -> { queued.add(call.getArgument(4)); return null; }).when(regions)
                .execute(any(), eq(bukkitWorld), eq(0), eq(0), any(Runnable.class));
        var releaseEntered = new CountDownLatch(1);
        var releaseAllowed = new CountDownLatch(1);
        when(manager.removeTicketAtLevel(any(TicketType.class), eq(0L), eq(33), any())).thenAnswer(call -> {
            releaseEntered.countDown();
            assertTrue(releaseAllowed.await(20, TimeUnit.SECONDS));
            return true;
        });
        var returnedStage = new CompletableFuture<Integer>();
        CompletableFuture<Void> idle = null;
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, 0, 0, 0, 0)).thenReturn(true);
            var body = ScarpetNativeWork.observeNative(null, () -> {
                var held = CarpetRegionLease.<CompletableFuture<Integer>>runValue(world, 0, 0, 0, 0, lease -> returnedStage);
                ScarpetNativeWork.record(held.thenCompose(value -> value));
                return null;
            });
            ScarpetNativeWork.trackNative(server, body);
            var actor = queued.poll(3, TimeUnit.SECONDS);
            assertNotNull(actor);
            actor.run();
            assertFalse(body.isDone());
            returnedStage.complete(7);
            body.get(3, TimeUnit.SECONDS);
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            ScarpetNativeWork.beginDrain(server);
            CarpetRegionLease.close(server);
            idle = ScarpetNativeWork.whenIdle(server);
            assertFalse(idle.isDone(), "Region schedulers must stay alive while HOLD ticket cleanup is running");
            releaseAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);
        } finally {
            releaseAllowed.countDown();
            returnedStage.complete(7);
            if (idle != null) idle.get(3, TimeUnit.SECONDS);
            CarpetRegionLease.close(server);
        }
    }

    @Test void shutdownCancelsIndependentWaitingAdmissionsBeforeDrainAndWaitsTheirActualCleanup() throws Exception {
        var fixture = new Fixture();
        var calls = new AtomicInteger();
        var releaseEntered = new CountDownLatch(1);
        var releaseAllowed = new CountDownLatch(1);
        fixture.blockRelease(releaseEntered, releaseAllowed);
        try {
            var waiting = CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> calls.incrementAndGet());
            var actor = fixture.nextActor();
            ScarpetNativeWork.beginDrain(fixture.server);
            CarpetRegionLease.beginShutdown(fixture.server);
            assertInstanceOf(IllegalStateException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS)).getCause());
            actor.run();
            assertEquals(0, calls.get());
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            var late = CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> calls.incrementAndGet());
            assertTrue(late.isCompletedExceptionally());
            assertTrue(fixture.queued.isEmpty());
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            releaseAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);

            // Closing one server must not reject another server's independent admission.
            var other = new Fixture();
            var open = CarpetRegionLease.runValue(other.world, 0, 0, 0, 0, lease -> 1);
            other.nextActor();
            assertFalse(open.isDone());
            CarpetRegionLease.beginShutdown(other.server);
            assertTrue(open.isCompletedExceptionally());
            ScarpetNativeWork.whenIdle(other.server).get(3, TimeUnit.SECONDS);
        } finally {
            releaseAllowed.countDown();
            CarpetRegionLease.beginShutdown(fixture.server);
            ScarpetNativeWork.whenIdle(fixture.server).get(3, TimeUnit.SECONDS);
        }
    }

    @Test void shutdownPreservesWaitingNativeActorsAndTheirLaterAcceptedLeaseContinuation() throws Exception {
        var fixture = new Fixture();
        var nativeToken = new AtomicReference<ScarpetNativeWork.Token>();
        var first = new AtomicReference<CompletableFuture<Integer>>();
        var calls = new AtomicInteger();
        var body = ScarpetNativeWork.observeNative(null, () -> {
            nativeToken.set(ScarpetNativeWork.capture());
            first.set(CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> calls.incrementAndGet()));
            ScarpetNativeWork.record(first.get());
            return null;
        });
        ScarpetNativeWork.trackNative(fixture.server, body);
        var firstActor = fixture.nextActor();
        ScarpetNativeWork.beginDrain(fixture.server);
        CarpetRegionLease.beginShutdown(fixture.server);
        assertFalse(first.get().isDone());
        var continuation = ScarpetNativeWork.with(nativeToken.get(), () -> {
            var next = CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> calls.incrementAndGet());
            ScarpetNativeWork.record(next);
            return next;
        });
        var secondActor = fixture.nextActor();
        var idle = ScarpetNativeWork.whenIdle(fixture.server);
        assertFalse(idle.isDone());
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(fixture.world, 0, 0, 0, 0)).thenReturn(true);
            firstActor.run();
            assertEquals(1, first.get().get(3, TimeUnit.SECONDS));
            assertFalse(body.isDone());
            assertFalse(idle.isDone());
            secondActor.run();
            assertEquals(2, continuation.get(3, TimeUnit.SECONDS));
        } finally {
            CarpetRegionLease.close(fixture.server);
        }
        body.get(3, TimeUnit.SECONDS);
        idle.get(3, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
    }

    @Test void schedulerRejectionKeepsDrainPendingUntilTheAcquiredTicketIsReleased() throws Exception {
        var fixture = new Fixture();
        var rejection = new java.util.concurrent.RejectedExecutionException("owner scheduler stopped");
        doThrow(rejection).when(fixture.regions).execute(any(), eq(fixture.bukkitWorld), eq(0), eq(0), any(Runnable.class));
        var releaseEntered = new CountDownLatch(1);
        var releaseAllowed = new CountDownLatch(1);
        fixture.blockRelease(releaseEntered, releaseAllowed);
        try {
            var actual = CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> fail("Rejected actor must not run"));
            assertSame(rejection, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            ScarpetNativeWork.beginDrain(fixture.server);
            CarpetRegionLease.beginShutdown(fixture.server);
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            releaseAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);
        } finally {
            releaseAllowed.countDown();
            CarpetRegionLease.close(fixture.server);
            ScarpetNativeWork.whenIdle(fixture.server).get(3, TimeUnit.SECONDS);
        }
    }

    @Test void cancellingTheCallerViewDoesNotCompleteTheActualTicketCleanupReceipt() throws Exception {
        var fixture = new Fixture();
        var calls = new AtomicInteger();
        var releaseEntered = new CountDownLatch(1);
        var releaseAllowed = new CountDownLatch(1);
        fixture.blockRelease(releaseEntered, releaseAllowed);
        try {
            var actual = CarpetRegionLease.runValue(fixture.world, 0, 0, 0, 0, lease -> calls.incrementAndGet());
            var actor = fixture.nextActor();
            assertTrue(actual.cancel(true));
            actor.run();
            assertEquals(0, calls.get());
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            releaseAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);
            assertTrue(actual.isCancelled());
        } finally {
            releaseAllowed.countDown();
            CarpetRegionLease.close(fixture.server);
            ScarpetNativeWork.whenIdle(fixture.server).get(3, TimeUnit.SECONDS);
        }
    }

    @Test void partialAcquisitionFailureStillDrainsAllAttemptedTicketReleases() throws Exception {
        var fixture = new Fixture();
        var failed = new IllegalStateException("second ticket acquisition failed");
        when(fixture.manager.addTicketAtLevel(any(), eq(1), eq(0), eq(33), any())).thenThrow(failed);
        var releaseEntered = new CountDownLatch(1);
        var releaseAllowed = new CountDownLatch(1);
        fixture.blockRelease(releaseEntered, releaseAllowed);
        try {
            var actual = CarpetRegionLease.runValue(fixture.world, 0, 0, 1, 0, lease -> fail("Failed acquisition must not run"));
            assertSame(failed, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(fixture.queued.isEmpty());
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            releaseAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);
            verify(fixture.manager).removeTicketAtLevel(any(), eq(0L), eq(33), any());
            verify(fixture.manager).removeTicketAtLevel(any(), eq(1L), eq(33), any());
        } finally {
            releaseAllowed.countDown();
            CarpetRegionLease.close(fixture.server);
            ScarpetNativeWork.whenIdle(fixture.server).get(3, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void nativeShutdownAlsoWaitsTheActualLoadedAreaTopologyUnpin() throws Exception {
        var fixture = new Fixture();
        Fixture.field(fixture.manager, "ticketLockArea", new ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock(4));
        Fixture.field(fixture.scheduler, "schedulingLockArea", new ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock(4));
        var callbacks = mock(io.papermc.paper.threadedregions.ThreadedRegionizer.RegionCallbacks.class);
        when(callbacks.createNewData(any())).thenAnswer(call -> mock(io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegionData.class));
        var topology = spy(new io.papermc.paper.threadedregions.ThreadedRegionizer(2, 0.5, 1, 1, 3, fixture.world, callbacks));
        Fixture.field(fixture.world, "regioniser", topology);
        var unpinEntered = new CountDownLatch(1);
        var unpinAllowed = new CountDownLatch(1);
        doAnswer(call -> {
            unpinEntered.countDown();
            assertTrue(unpinAllowed.await(20, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(topology).carpetUnpinSection(0, 0);
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(fixture.world, 0, 0, 0, 0)).thenReturn(true);
            var result = CarpetRegionLease.runLoadedValue(fixture.world, 0, 0, 0, 0, lease -> 4);
            fixture.nextActor().run();
            assertEquals(4, result.get(3, TimeUnit.SECONDS));
            assertTrue(unpinEntered.await(3, TimeUnit.SECONDS));
            ScarpetNativeWork.beginDrain(fixture.server);
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone(), "Native cleanup includes topology unpin after HOLD ticket removal");
            unpinAllowed.countDown();
            idle.get(3, TimeUnit.SECONDS);
            verify(topology).carpetUnpinSection(0, 0);
            verify(fixture.manager, never()).addTicketAtLevel(any(), anyInt(), anyInt(), anyInt(), any());
        } finally {
            unpinAllowed.countDown();
            CarpetRegionLease.close(fixture.server);
            ScarpetNativeWork.whenIdle(fixture.server).get(3, TimeUnit.SECONDS);
        }
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        final ChunkHolderManager manager = mock(ChunkHolderManager.class);
        final CraftWorld bukkitWorld = mock(CraftWorld.class);
        final RegionScheduler regions = mock(RegionScheduler.class);
        final LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();

        Fixture() throws Exception {
            field(scheduler, "chunkHolderManager", manager);
            when(world.getServer()).thenReturn(server);
            when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
            var loaded = mock(LevelChunk.class);
            when(world.getChunkIfLoaded(0, 0)).thenReturn(loaded);
            when(world.getWorld()).thenReturn(bukkitWorld);
            server.server = mock(CraftServer.class);
            when(server.server.getRegionScheduler()).thenReturn(regions);
            doAnswer(call -> { queued.add(call.getArgument(4)); return null; }).when(regions)
                    .execute(any(), eq(bukkitWorld), eq(0), eq(0), any(Runnable.class));
        }

        Runnable nextActor() throws Exception {
            var actor = queued.poll(3, TimeUnit.SECONDS);
            assertNotNull(actor);
            return actor;
        }

        void blockRelease(CountDownLatch entered, CountDownLatch allowed) {
            when(manager.removeTicketAtLevel(any(TicketType.class), eq(0L), eq(33), any())).thenAnswer(call -> {
                entered.countDown();
                assertTrue(allowed.await(20, TimeUnit.SECONDS));
                return true;
            });
        }

        static void field(Object value, String name, Object replacement) throws Exception {
            var field = value.getClass().getField(name);
            field.setAccessible(true);
            field.set(value, replacement);
        }
    }
}
