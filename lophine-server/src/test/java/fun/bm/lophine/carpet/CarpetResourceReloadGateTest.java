package fun.bm.lophine.carpet;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetResourceReloadGateTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void cancellingBeforeOwnersDrainReleasesTheGateAndAllowsTheNextReload() {
        try (var globals = mockStatic(RegionizedServer.class)) {
            var scheduler = mock(RegionizedServer.class);
            var server = mock(MinecraftServer.class);
            var tasks = new ArrayDeque<Runnable>();
            globals.when(RegionizedServer::getInstance).thenReturn(scheduler);
            doAnswer(call -> { tasks.addLast(call.getArgument(0)); return null; }).when(scheduler).addTask(any());
            when(server.getAllLevels()).thenReturn(List.of());
            var applied = new AtomicInteger();
            assertTrue(CarpetResourceReloadCoordinator.tryEnterRegion());
            try {
                var cancelled = CarpetResourceReloadCoordinator.exclusive(server, applied::incrementAndGet);
                tasks.removeFirst().run();
                assertTrue(CarpetResourceReloadCoordinator.regionsPaused());
                assertTrue(cancelled.cancel(false));
                tasks.removeFirst().run();
                assertFalse(CarpetResourceReloadCoordinator.regionsPaused());
                assertEquals(0, applied.get());
                assertTrue(CarpetResourceReloadCoordinator.tryEnterRegion());
                CarpetResourceReloadCoordinator.exitRegion();
            } finally { CarpetResourceReloadCoordinator.exitRegion(); }
            var next = CarpetResourceReloadCoordinator.exclusive(server, applied::incrementAndGet);
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            assertEquals(1, next.join());
            assertFalse(CarpetResourceReloadCoordinator.regionsPaused());
        }
    }

    @Test
    void cancellingAnAlreadyQueuedApplyDoesNotLeaveRegionsPaused() {
        try (var globals = mockStatic(RegionizedServer.class)) {
            var scheduler = mock(RegionizedServer.class);
            var server = mock(MinecraftServer.class);
            var tasks = new ArrayDeque<Runnable>();
            globals.when(RegionizedServer::getInstance).thenReturn(scheduler);
            doAnswer(call -> { tasks.addLast(call.getArgument(0)); return null; }).when(scheduler).addTask(any());
            when(server.getAllLevels()).thenReturn(List.of());
            var applied = new AtomicInteger();
            var result = CarpetResourceReloadCoordinator.exclusive(server, applied::incrementAndGet);
            tasks.removeFirst().run();
            assertTrue(CarpetResourceReloadCoordinator.regionsPaused());
            result.cancel(false);
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            assertEquals(0, applied.get());
            assertFalse(CarpetResourceReloadCoordinator.regionsPaused());
        }
    }

    @Test
    void cancellingAStartedApplyKeepsItsNativeGateUntilTheActionReturns() {
        try (var globals = mockStatic(RegionizedServer.class)) {
            var scheduler = mock(RegionizedServer.class);
            var server = mock(MinecraftServer.class);
            var tasks = new ArrayDeque<Runnable>();
            globals.when(RegionizedServer::getInstance).thenReturn(scheduler);
            doAnswer(call -> { tasks.addLast(call.getArgument(0)); return null; }).when(scheduler).addTask(any());
            when(server.getAllLevels()).thenReturn(List.of());
            var view = new AtomicReference<CompletableFuture<Integer>>();
            view.set(CarpetResourceReloadCoordinator.exclusive(server, () -> {
                assertTrue(CarpetResourceReloadCoordinator.regionsPaused());
                assertTrue(view.get().cancel(false));
                tasks.removeFirst().run(); // cancellation reaches global while native apply is still running
                assertTrue(CarpetResourceReloadCoordinator.regionsPaused());
                assertFalse(CarpetResourceReloadCoordinator.tryEnterRegion());
                return 1;
            }));
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            assertTrue(view.get().isCancelled());
            assertFalse(CarpetResourceReloadCoordinator.regionsPaused());
        }
    }

    @Test
    void ordinaryRegionEntryAndExitDoNotWaitForTheGlobalPauseMonitor() throws Exception {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        try (var pool = Executors.newSingleThreadExecutor()) {
            synchronized (gate) {
                pool.submit(() -> {
                    assertTrue(gate.enter());
                    gate.exit();
                }).get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void racingPauseAndResumeNeverAcknowledgesWhileAcceptedOwnersAreExecuting() throws Exception {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        var stop = new AtomicBoolean();
        var applying = new AtomicBoolean();
        var executing = new AtomicInteger();
        var ready = new CountDownLatch(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                ready.countDown();
                while (!stop.get()) {
                    if (!gate.enter()) {
                        Thread.onSpinWait();
                        continue;
                    }
                    executing.incrementAndGet();
                    try {
                        assertFalse(applying.get(), "Resource apply overlaps an accepted region owner");
                        Thread.yield();
                        assertFalse(applying.get(), "Resource apply begins before owner exit");
                    } finally {
                        executing.decrementAndGet();
                        gate.exit();
                    }
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 1000; i++) {
                    gate.pause().get(5, TimeUnit.SECONDS);
                    applying.set(true);
                    assertEquals(0, executing.get());
                    assertFalse(gate.enter());
                    applying.set(false);
                    gate.resume();
                }
            } finally {
                applying.set(false);
                gate.resume();
                stop.set(true);
            }
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
            gate.resume();
        }
    }

    @Test
    void ackWaitsForBothTickAndBetweenTickExecutionsAndRejectsNewSplitOrCreatedActors() {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        assertTrue(gate.enter()); // actual tick
        assertTrue(gate.enter()); // actual between-tick task batch
        var acknowledgement = gate.pause();
        assertTrue(gate.paused());
        assertFalse(acknowledgement.isDone());
        assertFalse(gate.enter()); // any new region identity goes through the same gate
        gate.exit();
        assertFalse(acknowledgement.isDone());
        gate.exit();
        assertTrue(acknowledgement.isDone());
        gate.resume();
        assertFalse(gate.paused());
        assertTrue(gate.enter());
        gate.exit();
    }

    @Test
    void ownersExitWithoutWaitingForGlobalAndManyRejectedEntrantsNeverJoinTheAck() throws Exception {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        var entered = new CountDownLatch(8);
        var leave = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; ++i) jobs.add(pool.submit(() -> {
                assertTrue(gate.enter());
                entered.countDown();
                try { assertTrue(leave.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                finally { gate.exit(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var acknowledgement = gate.pause();
            for (int i = 0; i < 10000; ++i) assertFalse(gate.enter());
            assertFalse(acknowledgement.isDone());
            leave.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            assertTrue(acknowledgement.isDone());
            gate.resume();
            assertTrue(gate.enter());
            gate.exit();
        } finally { leave.countDown(); gate.resume(); }
    }

    @Test
    void cancellingAWaitPreservesExistingReadersForTheNextBarrierAndDoesNotBlockThem() {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        assertTrue(gate.enter());
        var abandoned = gate.pause();
        gate.resume(); // timeout/shutdown cancellation
        assertTrue(gate.enter());
        var next = gate.pause();
        gate.exit();
        assertFalse(next.isDone());
        gate.exit();
        assertTrue(next.isDone());
        assertFalse(abandoned.isDone()); // old epoch cannot acknowledge the new generation
        gate.resume();
        for (int i = 0; i < 100; ++i) {
            assertTrue(gate.enter());
            var ready = gate.pause();
            gate.exit();
            assertTrue(ready.isDone());
            gate.resume();
        }
    }
}
