package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedTaskQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TisReadCommandLifetimeTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final Entity owner = mock(Entity.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final CommandResultCallback callback = mock(CommandResultCallback.class);
        final List<String> messages = new ArrayList<>();
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        CompletableFuture<Void> feedbackChild;

        Fixture() throws Exception {
            when(world.getServer()).thenReturn(server);
            when(world.dimension()).thenReturn(Level.OVERWORLD);
            when(owner.level()).thenReturn(world);
            when(owner.blockPosition()).thenReturn(BlockPos.ZERO);
            when(source.getServer()).thenReturn(server);
            when(source.getLevel()).thenReturn(world);
            when(source.getEntity()).thenReturn(owner);
            when(source.getEntityOrException()).thenReturn(owner);
            when(source.callback()).thenReturn(callback);
            when(source.getPosition()).thenReturn(Vec3.ZERO);
            doAnswer(call -> {
                messages.add(((Supplier<Component>) call.getArgument(0)).get().getString());
                if (feedbackChild != null) ScarpetNativeWork.record(feedbackChild);
                return null;
            }).when(source).sendSuccess(any(), anyBoolean());
        }

        org.mockito.MockedStatic<TickThread> ticks() {
            var ticks = mockStatic(TickThread.class);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(TickThread::isTickThread).thenReturn(true);
            return ticks;
        }

        org.mockito.MockedStatic<CarpetRegionLease> delayedLease() {
            var leases = mockStatic(CarpetRegionLease.class);
            leases.when(() -> CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)))
                    .thenAnswer(call -> {
                        var actual = new CompletableFuture<Object>();
                        Function<?, ?> body = call.getArgument(5);
                        queued.add(() -> {
                            try { actual.complete(body.apply(null)); }
                            catch (Throwable failure) { actual.completeExceptionally(failure); }
                        });
                        return actual;
                    });
            return leases;
        }

        org.mockito.MockedStatic<RegionizedServer> delayedRaidQueue() throws Exception {
            var regionized = mock(RegionizedServer.class);
            var queue = mock(RegionizedTaskQueue.class);
            var field = RegionizedServer.class.getField("taskQueue");
            field.setAccessible(true);
            field.set(regionized, queue);
            when(queue.queueTickTaskQueue(eq(world), anyInt(), anyInt(), any(Runnable.class)))
                    .thenAnswer(call -> { queued.add(call.getArgument(3)); return null; });
            var regions = mockStatic(RegionizedServer.class);
            regions.when(RegionizedServer::getInstance).thenReturn(regionized);
            return regions;
        }

        Raid raid(CompletableFuture<Void> child) throws Exception {
            var raid = mock(Raid.class);
            var raids = mock(Raids.class);
            var table = new ca.spottedleaf.concurrentutil.map.concurrent.ints.ConcurrentChainedInt2ReferenceHashTable<Raid>();
            table.put(1, raid);
            var field = Raids.class.getField("raidMap");
            field.setAccessible(true);
            field.set(raids, table);
            when(server.getAllLevels()).thenReturn(List.of(world));
            when(world.getRaids()).thenReturn(raids);
            when(raids.get(1)).thenReturn(raid);
            when(raid.getCenter()).thenReturn(BlockPos.ZERO);
            when(raid.carpetView()).thenAnswer(call -> {
                if (child != null) ScarpetNativeWork.record(child);
                return new Raid.CarpetRaidView(BlockPos.ZERO, "ongoing", 1, 1, 3, List.of(), null);
            });
            return raid;
        }
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... arguments) throws Exception {
        var method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    @SuppressWarnings("unchecked")
    private static CommandContext<CommandSourceStack> context(Fixture fixture) {
        var context = (CommandContext<CommandSourceStack>) mock(CommandContext.class);
        when(context.getSource()).thenReturn(fixture.source);
        when(context.getArgument("shapeMode", String.class)).thenThrow(new IllegalArgumentException("absent"));
        when(context.getArgument("fluidMode", String.class)).thenThrow(new IllegalArgumentException("absent"));
        return context;
    }

    private static org.mockito.MockedStatic<Vec3Argument> positions(CommandContext<CommandSourceStack> context) {
        var positions = mockStatic(Vec3Argument.class);
        positions.when(() -> Vec3Argument.getVec3(context, "start")).thenReturn(new Vec3(0.1, 0.1, 0.1));
        positions.when(() -> Vec3Argument.getVec3(context, "end")).thenReturn(new Vec3(4.1, 0.1, 0.1));
        return positions;
    }

    @Test void raycastResultWaitsForDelayedOwnerReadItsNativeChildrenAndReplyChildren() throws Exception {
        var fixture = new Fixture();
        var readChild = new CompletableFuture<Void>();
        fixture.feedbackChild = new CompletableFuture<>();
        when(fixture.world.clip(any(ClipContext.class), eq(BlockPos.ZERO))).thenAnswer(call -> {
            ScarpetNativeWork.record(readChild);
            return new BlockHitResult(Vec3.ZERO, net.minecraft.core.Direction.UP, BlockPos.ZERO, false);
        });
        when(fixture.world.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.STONE.defaultBlockState());
        var context = context(fixture);
        try (var ticks = fixture.ticks(); var leases = fixture.delayedLease(); var positions = positions(context);
             var scope = CarpetAsyncCommandResults.open()) {
            assertEquals(1, invoke(TisRaycastCommand.class, "raycast", new Class[]{CommandContext.class}, context));
            var actual = scope.resultFuture(fixture.source);
            assertFalse(actual.isDone());
            assertTrue(fixture.messages.isEmpty());
            fixture.queued.remove().run();
            assertTrue(fixture.messages.isEmpty());
            readChild.complete(null);
            assertEquals(1, fixture.messages.size());
            assertFalse(actual.isDone());
            verifyNoInteractions(fixture.callback);
            fixture.feedbackChild.complete(null);
            assertEquals(1, actual.join());
            verify(fixture.callback).onResult(true, 1);
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }

    @Test void raidListCancellationDoesNotSkipDelayedReadsOrReplyChildren() throws Exception {
        var fixture = new Fixture();
        var readChild = new CompletableFuture<Void>();
        fixture.feedbackChild = new CompletableFuture<>();
        fixture.raid(readChild);
        try (var ticks = fixture.ticks(); var regions = fixture.delayedRaidQueue(); var scope = CarpetAsyncCommandResults.open()) {
            assertEquals(1, invoke(TisRaidCommand.class, "list", new Class[]{CommandSourceStack.class, boolean.class}, fixture.source, false));
            scope.resultFuture(fixture.source).cancel(false);
            assertTrue(fixture.messages.isEmpty());
            assertFalse(scope.completionFuture().isDone());
            fixture.queued.remove().run();
            assertTrue(fixture.messages.isEmpty());
            readChild.complete(null);
            assertEquals(1, fixture.messages.size());
            assertFalse(scope.completionFuture().isDone());
            verifyNoInteractions(fixture.callback);
            fixture.feedbackChild.complete(null);
            scope.completionFuture().join();
            verify(fixture.callback).onResult(true, 1);
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }

    @Test void shutdownRejectsAQueuedRaycastBeforeReadingTheWorldAndCompletesFailure() throws Exception {
        var fixture = new Fixture();
        var context = context(fixture);
        try (var ticks = fixture.ticks(); var leases = fixture.delayedLease(); var positions = positions(context);
             var scope = CarpetAsyncCommandResults.open()) {
            invoke(TisRaycastCommand.class, "raycast", new Class[]{CommandContext.class}, context);
            ScarpetNativeWork.beginDrain(fixture.server);
            assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            fixture.queued.remove().run();
            assertEquals(0, scope.resultFuture(fixture.source).join());
            verify(fixture.world, never()).clip(any(ClipContext.class), any(BlockPos.class));
            verify(fixture.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }

    @Test void shutdownRejectsAQueuedRaidSnapshotAndCompletesFailure() throws Exception {
        var fixture = new Fixture();
        var raid = fixture.raid(null);
        try (var ticks = fixture.ticks(); var regions = fixture.delayedRaidQueue(); var scope = CarpetAsyncCommandResults.open()) {
            invoke(TisRaidCommand.class, "list", new Class[]{CommandSourceStack.class, boolean.class}, fixture.source, true);
            ScarpetNativeWork.beginDrain(fixture.server);
            fixture.queued.remove().run();
            assertEquals(0, scope.resultFuture(fixture.source).join());
            verify(raid, never()).carpetView();
            verify(fixture.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }

    @Test void continuouslyMovingRaidTerminatesItsOwnerRetriesAndReportsFailure() throws Exception {
        var fixture = new Fixture();
        var raid = fixture.raid(null);
        try (var ticks = fixture.ticks(); var regions = fixture.delayedRaidQueue(); var scope = CarpetAsyncCommandResults.open()) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(fixture.world), any(BlockPos.class))).thenReturn(false);
            invoke(TisRaidCommand.class, "list", new Class[]{CommandSourceStack.class, boolean.class}, fixture.source, true);
            for (int attempts = 0; attempts < 8; ++attempts) {
                assertFalse(fixture.queued.isEmpty());
                fixture.queued.remove().run();
            }
            assertTrue(fixture.queued.isEmpty());
            assertEquals(0, scope.resultFuture(fixture.source).join());
            verify(raid, never()).carpetView();
            verify(fixture.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
}
