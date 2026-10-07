package carpet.script.external;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

/** Actual handoff driver, with physical placement and region ownership supplied by the world fixture. */
public class ScarpetDimensionContinuationsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap(); }
    @Test void everyOriginalTransformFinishesBeforePlacementAndCancellationCannotReleaseTheNativeHandoff() {
        var server=mock(MinecraftServer.class);var origin=mock(ServerLevel.class);when(origin.getServer()).thenReturn(server);
        var root=mock(Entity.class);var rider=mock(Entity.class);var copy=mock(Entity.class);
        for(var entity:List.of(root,rider)){when(entity.level()).thenReturn(origin);when(entity.blockPosition()).thenReturn(BlockPos.ZERO);}
        var rootNode=new Entity.EntityTreeNode(null,root);var riderNode=new Entity.EntityTreeNode(rootNode,rider);
        var rootTransform=new CompletableFuture<Entity>();var riderTransform=new CompletableFuture<Entity>();
        var calls=new AtomicInteger();var placed=new AtomicReference<Consumer<Throwable>>();
        try(var ticks=mockStatic(TickThread.class)){
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(eq(origin),any(BlockPos.class))).thenReturn(true);
            var view=ScarpetDimensionContinuations.transformTree(root,origin,BlockPos.ZERO,List.of(rootNode,riderNode),
                entity->{calls.incrementAndGet();return entity==root?rootTransform:riderTransform;},placed::set,()->fail("Unexpected transform failure"));
            assertEquals(1,calls.get());assertNull(placed.get());assertTrue(view.cancel(false));
            var idle=ScarpetNativeRemovals.whenIdle(server);assertFalse(idle.isDone());
            rootTransform.complete(copy);assertSame(copy,rootNode.root);assertEquals(2,calls.get());assertNull(placed.get());
            riderTransform.complete(rider);assertNotNull(placed.get());assertFalse(idle.isDone());
            placed.get().accept(null);idle.join();assertTrue(view.isCancelled());
        }
    }

    @Test void aRealTransformFailureRunsOriginCleanupWithoutStartingDestinationPlacement() {
        var server=mock(MinecraftServer.class);var origin=mock(ServerLevel.class);when(origin.getServer()).thenReturn(server);
        var root=mock(Entity.class);when(root.level()).thenReturn(origin);when(root.blockPosition()).thenReturn(BlockPos.ZERO);
        var actual=new CompletableFuture<Entity>();var cleaned=new AtomicInteger();
        try(var ticks=mockStatic(TickThread.class)){
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(eq(origin),any(BlockPos.class))).thenReturn(true);
            var completion=ScarpetDimensionContinuations.transformTree(root,origin,BlockPos.ZERO,List.of(new Entity.EntityTreeNode(null,root)),
                entity->actual,finished->fail("Failed native transform cannot place"),cleaned::incrementAndGet);
            actual.completeExceptionally(new IllegalStateException("Actual transform failed"));
            assertThrows(java.util.concurrent.CompletionException.class,completion::join);assertEquals(1,cleaned.get());
            ScarpetNativeRemovals.whenIdle(server).handle((ignored,failure)->null).join();
        }
    }

    @Test void committedCompletionCarriesItsRootAndWaitsForCallbackNativeChildren() {
        var body=new CompletableFuture<Void>();var nested=new CompletableFuture<Void>();
        var callback=new AtomicReference<Consumer<Integer>>();var token=new AtomicReference<ScarpetNativeWork.Token>();
        var observed=ScarpetNativeWork.<Void>observeNative(null,()->{
            token.set(ScarpetNativeWork.capture());ScarpetNativeWork.record(body);
            callback.set(ScarpetDimensionContinuations.captureConsumer(value->{
                assertEquals(7,value);assertSame(token.get(),ScarpetNativeWork.capture());ScarpetNativeWork.record(nested);body.complete(null);
            }));return null;
        });
        callback.get().accept(7);assertFalse(observed.isDone());assertNull(ScarpetNativeWork.capture());
        nested.complete(null);observed.join();
    }

    private static final class OriginFixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel origin=mock(ServerLevel.class);
        final Entity root=mock(Entity.class);
        final java.util.concurrent.RejectedExecutionException failure=new java.util.concurrent.RejectedExecutionException("Origin scheduler retired");
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        boolean owner;

        OriginFixture() throws Exception {
            when(origin.getServer()).thenReturn(server);when(root.level()).thenReturn(origin);when(root.blockPosition()).thenReturn(BlockPos.ZERO);
            var craft=mock(org.bukkit.craftbukkit.CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var serverField=MinecraftServer.class.getField("server");serverField.setAccessible(true);serverField.set(server,craft);
            var scheduler=mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(scheduler);
            var craftWorld=mock(org.bukkit.craftbukkit.CraftWorld.class);when(origin.getWorld()).thenReturn(craftWorld);
            doThrow(failure).when(scheduler).execute(any(),any(org.bukkit.World.class),anyInt(),anyInt(),any(Runnable.class));
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(eq(origin),any(BlockPos.class))).thenAnswer(call->owner);
        }

        public void close() { bukkit.close();ticks.close(); }
    }

    @Test void rejectedOriginDispatchReturnsTheActualFailureWithoutRunningAnUnownedOperation() throws Exception {
        try(var fixture=new OriginFixture()) {
            var ran=new java.util.concurrent.atomic.AtomicBoolean();
            var actual=assertDoesNotThrow(()->ScarpetDimensionContinuations.atOrigin(fixture.origin,BlockPos.ZERO,()->{ran.set(true);return 13;}));
            assertTrue(actual.isCompletedExceptionally());
            assertSame(fixture.failure,assertThrows(java.util.concurrent.CompletionException.class,actual::join).getCause());assertFalse(ran.get());
        }
    }

    @Test void rejectedHandoffRunsCapturedCleanupOnceAndWaitsItsNativeChildrenWithoutLosingTheOriginalFailure() throws Exception {
        boolean previous=ScarpetRuntime.FILL_SKIP_UPDATES.get();
        var child=new CompletableFuture<Void>();
        try(var fixture=new OriginFixture()) {
            var transform=new CompletableFuture<Entity>();var cleanups=new AtomicInteger();
            var cleanupFailure=new IllegalStateException("Metadata lease cleanup failed");
            var completion=new AtomicReference<CompletableFuture<Void>>();
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
            var parent=ScarpetNativeWork.observeNative(fixture.root,()->{
                completion.set(ScarpetDimensionContinuations.transformTree(fixture.root,fixture.origin,BlockPos.ZERO,List.of(new Entity.EntityTreeNode(null,fixture.root)),
                    entity->transform,finished->fail("Rejected origin cannot place entities"),()->{
                        assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());assertNotNull(ScarpetNativeWork.capture());
                        cleanups.incrementAndGet();ScarpetNativeWork.record(child);throw cleanupFailure;
                    }));return 17;
            });
            ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
            var idle=ScarpetNativeRemovals.whenIdle(fixture.server);
            transform.complete(fixture.root);
            assertEquals(1,cleanups.get());assertFalse(completion.get().isDone());assertFalse(parent.isDone());assertFalse(idle.isDone());
            assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());assertNull(ScarpetNativeWork.capture());
            child.complete(null);
            assertTrue(completion.get().isCompletedExceptionally());
            assertSame(fixture.failure,assertThrows(java.util.concurrent.CompletionException.class,completion.get()::join).getCause());
            assertArrayEquals(new Throwable[]{cleanupFailure},fixture.failure.getSuppressed());
            assertTrue(parent.isCompletedExceptionally());assertTrue(idle.isDone());assertEquals(1,cleanups.get());
        } finally { child.complete(null);ScarpetRuntime.FILL_SKIP_UPDATES.set(previous); }
    }

    @Test void aPlacementWhichAlreadyReleasedItsLeaseCannotRunFailureCleanupAgain() throws Exception {
        try(var fixture=new OriginFixture()) {
            fixture.owner=true;var cleanups=new AtomicInteger();var failure=new IllegalStateException("Placement callback failed after terminal completion");
            var completion=ScarpetDimensionContinuations.transformTree(fixture.root,fixture.origin,BlockPos.ZERO,List.of(new Entity.EntityTreeNode(null,fixture.root)),
                entity->CompletableFuture.completedFuture(entity),finished->{finished.accept(null);throw failure;},cleanups::incrementAndGet);
            assertTrue(completion.isCompletedExceptionally());
            assertSame(failure,assertThrows(java.util.concurrent.CompletionException.class,completion::join).getCause());
            assertEquals(0,cleanups.get());assertTrue(ScarpetNativeRemovals.whenIdle(fixture.server).isDone());
        }
    }
}
