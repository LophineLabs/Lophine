package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetExplosionGuestOuterTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 @Test void guestOnlyExplosionWaitsNativeSiblingThenDiscardAndRemovalWhileOriginalParentStillFails(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);
  var guest=new CompletableFuture<Void>();var nativeReceipt=new CompletableFuture<Void>();var removal=new CompletableFuture<Void>();var order=new ArrayList<String>();var ready=new AtomicReference<CompletableFuture<Void>>();
  try(var actors=mockStatic(ScarpetExplosionActors.class)){
   actors.when(()->ScarpetExplosionActors.entity(eq(owner),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var original=ScarpetNativeWork.observeNative(owner,()->{ready.set(ScarpetExplosionContinuations.run(owner,()->{order.add("EXP");ScarpetNativeWork.record(guest);ScarpetNativeWork.record(nativeReceipt);},()->{order.add("ON_REMOVED head");ScarpetNativeWork.record(removal);}));return null;});
   var failure=new IllegalStateException("true guest callback failure");ScarpetNativeWork.markGuestFailure(failure);guest.completeExceptionally(failure);assertEquals(List.of("EXP"),order);assertFalse(ready.get().isDone());nativeReceipt.complete(null);assertEquals(List.of("EXP","ON_REMOVED head"),order);assertFalse(ready.get().isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());removal.complete(null);ready.get().join();assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
 @Test void nativeFailureAfterGuestFailureStillStopsRealDiscard(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);var guest=new CompletableFuture<Void>();var nativeReceipt=new CompletableFuture<Void>();var discard=new AtomicInteger();
  var actual=ScarpetExplosionContinuations.run(owner,()->{ScarpetNativeWork.record(guest);ScarpetNativeWork.record(nativeReceipt);},discard::incrementAndGet);var failedGuest=new IllegalStateException("guest");ScarpetNativeWork.markGuestFailure(failedGuest);guest.completeExceptionally(failedGuest);assertFalse(actual.isDone());nativeReceipt.completeExceptionally(new IllegalStateException("native packet failure"));assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));assertEquals(0,discard.get());
 }
 @Test void scalarOwnerPhaseRecoversOnlyItsRealBodyValueAndWaitsActualChildren(){
  var owner=mock(Entity.class);var guest=new CompletableFuture<Void>();var child=new CompletableFuture<Void>();var ready=new AtomicReference<CompletableFuture<String>>();
  try(var actors=mockStatic(ScarpetExplosionActors.class)){
   actors.when(()->ScarpetExplosionActors.entity(eq(owner),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var original=ScarpetNativeWork.observeNative(owner,()->{ready.set(ScarpetExplosionContinuations.targetEffect(owner,()->{ScarpetNativeWork.record(guest);ScarpetNativeWork.record(child);return "actual effect value";}));return null;});
   var failure=new IllegalStateException("guest");ScarpetNativeWork.markGuestFailure(failure);guest.completeExceptionally(failure);assertFalse(ready.get().isDone());child.complete(null);assertEquals("actual effect value",ready.get().join());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));
  }
 }
 @Test void nativeSupplierThrowCannotBeRecoveredEvenIfItReusesGuestExceptionIdentity(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);var owner=mock(Entity.class);when(world.getServer()).thenReturn(server);when(owner.level()).thenReturn(world);var failure=new IllegalStateException("previous guest");ScarpetNativeWork.markGuestFailure(failure);var tail=new AtomicInteger();
  var actual=ScarpetExplosionContinuations.run(owner,()->{throw failure;},tail::incrementAndGet);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));assertEquals(0,tail.get());
 }
}
