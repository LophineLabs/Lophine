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

/** Actual handoff driver, with physical placement and region ownership supplied by the world fixture. */
public class ScarpetDimensionContinuationsTest {
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
}
