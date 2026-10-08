package fun.bm.lophine.carpet;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisChunkActualPhasesTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel world = mock(ServerLevel.class);
    private final ServerChunkCache cache = mock(ServerChunkCache.class);

    @BeforeEach
    void setup() {
        when(world.getServer()).thenReturn(server);
        when(world.getChunkSource()).thenReturn(cache);
    }

    private org.mockito.MockedStatic<TickThread> owner() {
        var ticks = mockStatic(TickThread.class);
        ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
        ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
        ticks.when(TickThread::isTickThread).thenReturn(true);
        return ticks;
    }

    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private LevelChunk eraseChunk(List<Entity> entities, List<BlockPos> blocks) throws Exception {
        var chunk = mock(LevelChunk.class);
        field(chunk, LevelChunk.class, "level", world);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(chunk.carpetEraseEntitySnapshot()).thenReturn(entities);
        when(chunk.carpetEraseBlockEntitySnapshot()).thenReturn(blocks);
        doCallRealMethod().when(chunk).carpetEraseContentsAsync();
        return chunk;
    }

    @Test
    void actualNativeEraseEntryWaitsEachRemovalBeforeBlockEntitiesAndMatter() throws Exception {
        var first = mock(Entity.class);
        var second = mock(Entity.class);
        for (var actor : List.of(first, second)) {
            when(actor.level()).thenReturn(world);
            when(actor.blockPosition()).thenReturn(BlockPos.ZERO);
        }
        var chunk = eraseChunk(List.of(first, second), List.of(BlockPos.ZERO));
        var one = new CompletableFuture<Void>();
        var two = new CompletableFuture<Void>();
        var be = new CompletableFuture<Void>();
        var order = new ArrayList<String>();
        doAnswer(call -> {
            order.add("entity1");
            ScarpetNativeWork.record(one);
            return null;
        }).when(first).discard();
        doAnswer(call -> {
            order.add("entity2");
            ScarpetNativeWork.record(two);
            return null;
        }).when(second).discard();
        doAnswer(call -> {
            order.add("blockentity");
            ScarpetNativeWork.record(be);
            return null;
        }).when(world).removeBlockEntity(BlockPos.ZERO);
        when(chunk.carpetEraseContentsAfterRemovals(2, 1)).thenAnswer(call -> {
            order.add("matter");
            return new int[]{2, 1, 3, 4, 5};
        });
        try (var ticks = owner()) {
            var actual = chunk.carpetEraseContentsAsync();
            assertEquals(List.of("entity1"), order);
            one.complete(null);
            assertEquals(List.of("entity1", "entity2"), order);
            two.complete(null);
            assertEquals(List.of("entity1", "entity2", "blockentity"), order);
            assertFalse(actual.isDone());
            be.complete(null);
            assertArrayEquals(new int[]{2, 1, 3, 4, 5}, actual.join());
            assertEquals("matter", order.getLast());
        }
    }

    @Test
    void canceledEraseViewRetainsTrueNativeDrainAndAcceptedTail() throws Exception {
        var entity = mock(Entity.class);
        when(entity.level()).thenReturn(world);
        when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return null;
        }).when(entity).discard();
        var chunk = eraseChunk(List.of(entity), List.of());
        when(chunk.carpetEraseContentsAfterRemovals(1, 0)).thenReturn(new int[]{1, 0, 0, 0, 1});
        try (var ticks = owner()) {
            var view = chunk.carpetEraseContentsAsync();
            view.cancel(false);
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            child.complete(null);
            ScarpetNativeWork.whenIdle(server).join();
            verify(chunk).carpetEraseContentsAfterRemovals(1, 0);
        }
    }

    @Test
    void trueRemovalFailureStopsMatterWithoutPretendingSuccess() throws Exception {
        var entity = mock(Entity.class);
        when(entity.level()).thenReturn(world);
        when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return null;
        }).when(entity).discard();
        var chunk = eraseChunk(List.of(entity), List.of(BlockPos.ZERO));
        try (var ticks = owner()) {
            var actual = chunk.carpetEraseContentsAsync();
            child.completeExceptionally(new IllegalStateException("real native discard failure"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, actual::join)));
            verify(world, never()).removeBlockEntity(any());
            verify(chunk, never()).carpetEraseContentsAfterRemovals(anyInt(), anyInt());
        }
    }

    private record Lighting(LevelChunk chunk, ThreadedLevelLightEngine light, StarLightInterface engine,
                            ChunkHolderManager manager, PrioritisedExecutor.PrioritisedTask task,
                            AtomicReference<Runnable> work) {
    }

    private Lighting lighting() throws Exception {
        var chunk = mock(LevelChunk.class);
        when(chunk.isLightCorrect()).thenReturn(true);
        when(chunk.getPersistedStatus()).thenReturn(ChunkStatus.FULL);
        when(cache.getChunkForLighting(0, 0)).thenReturn(chunk);
        var light = mock(ThreadedLevelLightEngine.class);
        when(cache.getLightEngine()).thenReturn(light);
        var engine = mock(StarLightInterface.class);
        when(light.starlight$getLightEngine()).thenReturn(engine);
        var scheduler = mock(ChunkTaskScheduler.class);
        when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
        var manager = mock(ChunkHolderManager.class);
        field(scheduler, ChunkTaskScheduler.class, "chunkHolderManager", manager);
        var queue = mock(AreaDependentQueue.class);
        field(scheduler, ChunkTaskScheduler.class, "radiusAwareScheduler", queue);
        var task = mock(PrioritisedExecutor.PrioritisedTask.class);
        when(task.queue()).thenReturn(true);
        var work = new AtomicReference<Runnable>();
        when(queue.createTask(anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            work.set(call.getArgument(4));
            return task;
        });
        return new Lighting(chunk, light, engine, manager, task, work);
    }

    @Test
    void actualRelighterOutcomeIsAfterWorkerEngineAndTicketCleanupChildren() throws Exception {
        try (var ticks = owner()) {
            var data = lighting();
            var child = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            doAnswer(call -> {
                ((java.util.function.IntConsumer) call.getArgument(2)).accept(1);
                order.add("engine returned");
                return null;
            }).when(data.engine()).relightChunks(anySet(), isNull(), any());
            doAnswer(call -> {
                order.add("ticket cleanup");
                ScarpetNativeWork.record(child);
                return false;
            }).when(data.manager()).removeTicketAtLevel(eq(ChunkTaskScheduler.CHUNK_RELIGHT), eq(new ChunkPos(0, 0)), eq(StarLightInterface.LIGHT_TICKET_LEVEL), anyLong());
            var actual = TisChunkRelighter.relight(world, List.of(new ChunkPos(0, 0)));
            assertFalse(actual.isDone());
            data.work().get().run();
            assertEquals(List.of("engine returned", "ticket cleanup"), order);
            assertFalse(actual.isDone());
            child.complete(null);
            assertEquals(1, actual.join());
        }
    }

    @Test
    void cancelRelightViewCannotReleaseWorkerEarly() throws Exception {
        try (var ticks = owner()) {
            var data = lighting();
            doAnswer(call -> {
                ((java.util.function.IntConsumer) call.getArgument(2)).accept(1);
                return null;
            }).when(data.engine()).relightChunks(anySet(), isNull(), any());
            var actual = TisChunkRelighter.relight(world, List.of(new ChunkPos(0, 0)));
            actual.cancel(false);
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            data.work().get().run();
            ScarpetNativeWork.whenIdle(server).join();
            verify(data.manager()).removeTicketAtLevel(eq(ChunkTaskScheduler.CHUNK_RELIGHT), eq(new ChunkPos(0, 0)), eq(StarLightInterface.LIGHT_TICKET_LEVEL), anyLong());
        }
    }

    @Test
    void rejectedWorkerCleansTicketsAndQueuedLateRunnableCannotRunEffects() throws Exception {
        try (var ticks = owner()) {
            var data = lighting();
            when(data.task().queue()).thenReturn(false);
            var actual = TisChunkRelighter.relight(world, List.of(new ChunkPos(0, 0)));
            assertThrows(CompletionException.class, actual::join);
            data.work().get().run();
            verifyNoInteractions(data.engine());
            verify(data.manager(), times(1)).removeTicketAtLevel(eq(ChunkTaskScheduler.CHUNK_RELIGHT), eq(new ChunkPos(0, 0)), eq(StarLightInterface.LIGHT_TICKET_LEVEL), anyLong());
        }
    }

    @Test
    void eraseLightUsesRealVisibleZeroBlockFullSkyArraysInsteadOfRelightAlgorithm() throws Exception {
        try (var ticks = owner()) {
            var data = lighting();
            var dimension = mock(net.minecraft.world.level.dimension.DimensionType.class);
            when(world.dimensionType()).thenReturn(dimension);
            when(dimension.hasSkyLight()).thenReturn(true);
            when(data.chunk().getSections()).thenReturn(new LevelChunkSection[]{mock(LevelChunkSection.class), mock(LevelChunkSection.class)});
            for (var section : data.chunk().getSections()) when(section.hasOnlyAir()).thenReturn(true);
            doCallRealMethod().when(data.chunk()).starlight$setBlockNibbles(any());
            doCallRealMethod().when(data.chunk()).starlight$setSkyNibbles(any());
            doCallRealMethod().when(data.chunk()).starlight$getBlockNibbles();
            doCallRealMethod().when(data.chunk()).starlight$getSkyNibbles();
            data.chunk().starlight$setBlockNibbles(new SWMRNibbleArray[4]);
            data.chunk().starlight$setSkyNibbles(new SWMRNibbleArray[4]);
            var actual = TisChunkRelighter.eraseLight(world, new ChunkPos(0, 0));
            assertFalse(actual.isDone());
            data.work().get().run();
            assertEquals(1, actual.join());
            verifyNoInteractions(data.engine());
            for (var nibble : data.chunk().starlight$getBlockNibbles()) {
                assertEquals(0, nibble.getVisible(0));
                assertEquals(0, nibble.getVisible(4095));
            }
            for (var nibble : data.chunk().starlight$getSkyNibbles()) {
                assertEquals(15, nibble.getVisible(0));
                assertEquals(15, nibble.getVisible(4095));
            }
        }
    }

    @Test
    void chunkCommandQueryCountWaitsActualNativeBodiesAndTheirReports() throws Exception {
        var source = mock(CommandSourceStack.class);
        var player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(world);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(source.getEntity()).thenReturn(player);
        when(source.getLevel()).thenReturn(world);
        when(source.getServer()).thenReturn(server);
        var callback = mock(CommandResultCallback.class);
        when(source.callback()).thenReturn(callback);
        var first = mock(LevelChunk.class);
        var second = mock(LevelChunk.class);
        when(world.getChunkIfLoaded(0, 0)).thenReturn(first);
        when(world.getChunkIfLoaded(1, 0)).thenReturn(second);
        var one = new CompletableFuture<Void>();
        var two = new CompletableFuture<Void>();
        when(first.getInhabitedTime()).thenAnswer(call -> {
            ScarpetNativeWork.record(one);
            return 5L;
        });
        when(second.getInhabitedTime()).thenAnswer(call -> {
            ScarpetNativeWork.record(two);
            return 10L;
        });
        Class<?> action = Class.forName("fun.bm.lophine.carpet.TisManipulateChunks$Action");
        Object query = Enum.valueOf((Class) action, "QUERY");
        var context = mock(com.mojang.brigadier.context.CommandContext.class);
        when(context.getSource()).thenReturn(source);
        var run = TisManipulateChunks.class.getDeclaredMethod("operate", com.mojang.brigadier.context.CommandContext.class, action, List.class, boolean.class);
        run.setAccessible(true);
        try (var ticks = owner(); var lease = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open(); var scope = CarpetAsyncCommandResults.open()) {
            lease.when(() -> CarpetRegionLease.runLoadedValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(java.util.function.Function.class))).thenAnswer(call -> CompletableFuture.completedFuture(((java.util.function.Function) call.getArgument(5)).apply(null)));
            assertEquals(2, run.invoke(null, context, query, List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), true));
            assertFalse(scope.completionFuture().isDone());
            verifyNoInteractions(callback);
            one.complete(null);
            assertFalse(scope.completionFuture().isDone());
            two.complete(null);
            scope.completionFuture().join();
            verify(callback).onResult(true, 2);
        }
    }
}
