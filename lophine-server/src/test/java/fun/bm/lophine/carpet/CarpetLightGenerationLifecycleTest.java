package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkLightTask;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetLightGenerationLifecycleTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        final StarLightInterface engine = mock(StarLightInterface.class);
        final StarLightInterface.ServerLightQueue queue;
        final List<Runnable> workers = new ArrayList<>();
        final AtomicInteger lights = new AtomicInteger();

        Fixture() throws Exception {
            when(world.getServer()).thenReturn(server);
            when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
            when(engine.getWorld()).thenReturn(world);
            var area = mock(AreaDependentQueue.class);
            var field = ChunkTaskScheduler.class.getField("radiusAwareScheduler");
            field.setAccessible(true);
            field.set(scheduler, area);
            when(area.createTask(anyInt(), anyInt(), anyInt(), any(Runnable.class), any(Priority.class))).thenAnswer(call -> {
                workers.add(call.getArgument(3));
                var task = mock(PrioritisedExecutor.PrioritisedTask.class);
                when(task.queue()).thenReturn(true);
                return task;
            });
            queue = new StarLightInterface.ServerLightQueue(engine);
            when(engine.getServerLightQueue()).thenReturn(queue);
            var source = mock(ServerChunkCache.class);
            var light = mock(ThreadedLevelLightEngine.class);
            when(world.getChunkSource()).thenReturn(source);
            when(source.getLightEngine()).thenReturn(light);
            when(light.starlight$getLightEngine()).thenReturn(engine);
            doAnswer(call -> { lights.incrementAndGet(); return null; }).when(engine).lightChunk(any(), any());
        }

        ChunkLightTask generation(CompletableFuture<ChunkAccess> complete) {
            var chunk = mock(ChunkAccess.class);
            when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
            var task = new ChunkLightTask(scheduler, world, 0, 0, chunk, Priority.NORMAL);
            task.onComplete((ready, failure) -> {
                if (failure == null) complete.complete(ready);
                else complete.completeExceptionally(failure);
            });
            return task;
        }

        CompletableFuture<Void> startWorker(CountDownLatch started) {
            var done = new CompletableFuture<Void>();
            Thread.ofPlatform().daemon(true).name("Carpet light lifecycle test").start(() -> {
                started.countDown();
                try { workers.getFirst().run(); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            });
            return done;
        }
    }

    @Test void offModeRetainsAnActualGenerationTaskThatResumesAndCompletesWhenLightUpdatesReturn() throws Exception {
        String previous = GeneralCompatConfig.lightUpdates;
        CompletableFuture<Void> worker = null;
        try {
            GeneralCompatConfig.lightUpdates = "off";
            var fixture = new Fixture();
            var completed = new CompletableFuture<ChunkAccess>();
            var task = fixture.generation(completed);
            task.schedule();
            assertTrue(task.isScheduled());
            assertEquals(1, fixture.workers.size(), "A native LIGHT task needs a retained scheduler admission");
            var pending = fixture.queue.carpetSnapshotPendingTasks();
            var started = new CountDownLatch(1);
            worker = fixture.startWorker(started);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var running = worker;
            assertThrows(TimeoutException.class, () -> running.get(30, TimeUnit.MILLISECONDS));
            assertFalse(completed.isDone());
            assertFalse(pending.isDone());
            GeneralCompatConfig.lightUpdates = "on";
            worker.get(3, TimeUnit.SECONDS);
            assertNotNull(completed.get(3, TimeUnit.SECONDS));
            pending.get(3, TimeUnit.SECONDS);
            assertEquals(1, fixture.lights.get());
            assertTrue(fixture.queue.isEmpty());
        } finally {
            GeneralCompatConfig.lightUpdates = "on";
            if (worker != null) worker.get(3, TimeUnit.SECONDS);
            GeneralCompatConfig.lightUpdates = previous;
        }
    }

    @Test void nativeDrainFinishesAnAlreadyAcceptedSuppressedLightTaskAndItsRegisteredReceipt() throws Exception {
        String previous = GeneralCompatConfig.lightUpdates;
        CompletableFuture<Void> worker = null;
        try {
            GeneralCompatConfig.lightUpdates = "on";
            var fixture = new Fixture();
            var nativeReceipt = new CompletableFuture<Void>();
            var lights = new AtomicInteger();
            var task = fixture.queue.queueChunkLightTask(new ChunkPos(0, 0), () -> { lights.incrementAndGet(); return true; }, Priority.NORMAL);
            task.queueOrRunTask(() -> nativeReceipt.complete(null));
            ScarpetNativeWork.trackNative(fixture.server, nativeReceipt);
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            var pending = fixture.queue.carpetSnapshotPendingTasks();
            GeneralCompatConfig.lightUpdates = "suppressed";
            var started = new CountDownLatch(1);
            worker = fixture.startWorker(started);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var running = worker;
            assertThrows(TimeoutException.class, () -> running.get(30, TimeUnit.MILLISECONDS));
            assertFalse(idle.isDone());
            ScarpetNativeWork.beginDrain(fixture.server);
            worker.get(3, TimeUnit.SECONDS);
            pending.get(3, TimeUnit.SECONDS);
            idle.get(3, TimeUnit.SECONDS);
            assertEquals("suppressed", GeneralCompatConfig.lightUpdates);
            assertEquals(1, lights.get());
            assertTrue(fixture.queue.isEmpty());
        } finally {
            GeneralCompatConfig.lightUpdates = "on";
            if (worker != null) worker.get(3, TimeUnit.SECONDS);
            GeneralCompatConfig.lightUpdates = previous;
        }
    }
}
