package carpet.script.external;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.core.particles.ParticleTypes;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetServerLevelExplosionFactoryTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private static ServerLevel world(MinecraftServer server)throws Exception{
  var world=mock(ServerLevel.class,CALLS_REAL_METHODS);var field=ServerLevel.class.getDeclaredField("server");field.setAccessible(true);field.set(world,server);return world;
 }
 @Test void actualNativeFactoryWaitsMemoBeforeAnyFullLeaseAndCancellationAvoidsPackets()throws Exception{
  var server=mock(MinecraftServer.class);var world=world(server);var start=new CompletableFuture<Boolean>();
  try(var actors=mockStatic(ScarpetExplosionActors.class);var packets=mockStatic(ScarpetExplosionPackets.class);var leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);
      var constructed=mockConstruction(ServerExplosion.class,(exp,context)->{when(exp.carpetStartDecisionAsync()).thenReturn(start);when(exp.carpetExplodeAsync()).thenAnswer(call->{exp.wasCanceled=true;return CompletableFuture.completedFuture(0);});})){
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   var actual=world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
   assertFalse(actual.isDone());leases.verifyNoInteractions();start.complete(true);assertTrue(actual.isDone());assertTrue(actual.join().wasCanceled);leases.verifyNoInteractions();packets.verifyNoInteractions();
  }
 }
 @Test void actualNativeFactoryRetainsOuterFullLeaseAndWholeRegistryThroughTruePacketReceipts()throws Exception{
  var server=mock(MinecraftServer.class);var world=world(server);var start=new CompletableFuture<Boolean>();var core=new CompletableFuture<Integer>();var receipts=new CompletableFuture<Void>();var held=new java.util.concurrent.atomic.AtomicBoolean();
  try(var actors=mockStatic(ScarpetExplosionActors.class);var packets=mockStatic(ScarpetExplosionPackets.class);var leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);
      var constructed=mockConstruction(ServerExplosion.class,(exp,context)->{when(exp.carpetStartDecisionAsync()).thenReturn(start);when(exp.carpetExplodeAsync()).thenReturn(core);})){ 
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
    held.set(true);var nested=(CompletableFuture<?>)((Function<?,?>)call.getArgument(5)).apply(null);nested.whenComplete((value,failure)->held.set(false));return CompletableFuture.completedFuture(nested);
   });
   packets.when(()->ScarpetExplosionPackets.send(any(),eq(17),any(),any(),any(),any())).thenAnswer(call->{assertTrue(held.get());assertNotNull(ScarpetNativeWork.capture());return receipts;});
   var actual=world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
   assertFalse(held.get());start.complete(false);assertTrue(held.get());var drain=ScarpetNativeWork.whenIdle(server);actual.cancel(false);assertFalse(drain.isDone());
   core.complete(17);assertTrue(held.get());assertFalse(drain.isDone());receipts.complete(null);assertFalse(held.get());assertTrue(drain.isDone());assertTrue(actual.isCancelled());
  }
 }
}
