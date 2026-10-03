package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetExplosionOuterContinuationsTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void realWholeObserverWaitsPacketsOwnerCommitAndDynamicChildAfterCallerCancellation(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);
  var packets=new CompletableFuture<Void>();var afterDiscard=new CompletableFuture<Void>();var order=new ArrayList<String>();
  try(var actors=mockStatic(ScarpetExplosionActors.class)){
   actors.when(()->ScarpetExplosionActors.entity(eq(owner),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var visible=ScarpetExplosionContinuations.run(owner,()->{order.add("explosion");ScarpetNativeWork.record(packets);},()->{assertNotNull(ScarpetNativeWork.capture());order.add("real owner discard");ScarpetNativeWork.record(afterDiscard);});
   var drain=ScarpetNativeWork.whenIdle(server);visible.cancel(false);assertFalse(drain.isDone());assertEquals(List.of("explosion"),order);
   packets.complete(null);assertEquals(List.of("explosion","real owner discard"),order);assertFalse(drain.isDone());afterDiscard.complete(null);assertTrue(drain.isDone());assertTrue(visible.isCancelled());
  }
 }
 @Test void actualExplosionFailurePreventsSuccessTailButReleasesRealRegistryAtTerminal(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);
  var nativeTail=new CompletableFuture<Void>();var invoked=new java.util.concurrent.atomic.AtomicInteger();
  var actual=ScarpetExplosionContinuations.run(owner,()->ScarpetNativeWork.record(nativeTail),invoked::incrementAndGet);var drain=ScarpetNativeWork.whenIdle(server);
  assertFalse(drain.isDone());nativeTail.completeExceptionally(new IllegalStateException("actual native failure"));assertTrue(actual.isCompletedExceptionally());assertEquals(0,invoked.get());assertTrue(drain.isDone());
 }
 @Test void repeatedNativeTicksCannotPrimeAnotherExplosionWhileItsTruePacketTailRemains(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);
  var packets=new CompletableFuture<Void>();var calls=new java.util.concurrent.atomic.AtomicInteger();
  try(var actors=mockStatic(ScarpetExplosionActors.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   actors.when(()->ScarpetExplosionActors.entity(eq(owner),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var first=ScarpetExplosionContinuations.runOnce(owner,()->{calls.incrementAndGet();ScarpetNativeWork.record(packets);},()->{});
   assertTrue(ScarpetExplosionContinuations.pending(owner));var second=ScarpetExplosionContinuations.runOnce(owner,calls::incrementAndGet,()->{});assertEquals(1,calls.get());
   first.cancel(false);assertTrue(ScarpetExplosionContinuations.pending(owner));packets.complete(null);assertTrue(second.isDone());assertFalse(ScarpetExplosionContinuations.pending(owner));assertEquals(1,calls.get());
  }
 }
}
