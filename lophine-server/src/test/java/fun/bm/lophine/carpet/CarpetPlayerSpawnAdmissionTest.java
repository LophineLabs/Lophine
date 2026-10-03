package fun.bm.lophine.carpet;
import carpet.script.external.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.storage.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetPlayerSpawnAdmissionTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualGuestAndOwnerRestorationContinuationOperateAdmittedBornPlayerWhileOrdinaryInputRemainsPaused() throws Exception {
  try(var f=new CarpetPlayerSpawnContinuationsTest.Fixture()){
   var restored=f.entity("born");var birth=new CompletableFuture<Void>();CarpetPlayerBirths.admitPlayer(f.player,birth);
   assertTrue(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetPlayerInventoryGate.whenOpen(f.player).isDone());
   var ownerActual=new CompletableFuture<Void>();var guest=new AtomicReference<CompletableFuture<Runnable>>();var effects=new AtomicInteger();
   f.types.when(()->EntityType.loadEntityRecursive(any(ValueInput.class),eq(f.world),eq(EntitySpawnReason.LOAD),eq(EntityProcessor.NOP))).thenAnswer(invocation->{
    assertFalse(ScarpetPlayerInventoryGate.paused(f.player));ScarpetNativeWork.record(ownerActual);
    guest.set(ScarpetRuntime.of(f.server).submit(()->{
     assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
     var actualOwner=ScarpetRuntime.captureNativeContinuation(()->{
      assertFalse(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetPlayerInventoryGate.deferPaused(f.player,()->fail("accepted old birth must not queue behind itself")));
      effects.incrementAndGet();ownerActual.complete(null);return null;
     });return ()->actualOwner.get();
    }));return restored;
   });
   var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("born")));f.pump();
   assertFalse(actual.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetPlayerInventoryGate.whenOpen(f.player).isDone());
   Runnable returned=guest.get().get(10,TimeUnit.SECONDS);returned.run();f.pump();actual.join();assertEquals(1,effects.get());
   assertTrue(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetPlayerInventoryGate.whenOpen(f.player).isDone());birth.complete(null);assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
  }
 }
 @Test void acceptedRestoreScopeNeverLeaksOutsideNativeOwnerBody(){try(var f=new CarpetPlayerSpawnContinuationsTest.Fixture()){
  f.entity("born");var birth=new CompletableFuture<Void>();CarpetPlayerBirths.admitPlayer(f.player,birth);var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("born")));assertTrue(ScarpetPlayerInventoryGate.paused(f.player));f.pump();actual.join();assertTrue(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetPlayerInventoryGate.captureAccepted().contains(f.player));birth.complete(null);
 }}
}
