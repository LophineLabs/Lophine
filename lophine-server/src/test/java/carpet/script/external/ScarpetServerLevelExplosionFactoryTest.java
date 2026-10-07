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
  try(var actors=mockStatic(ScarpetExplosionActors.class);var packets=mockStatic(ScarpetExplosionPackets.class);var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
      var constructed=mockConstruction(ServerExplosion.class,(exp,context)->{when(exp.carpetStartDecisionAsync()).thenReturn(start);when(exp.carpetExplodeAsync()).thenAnswer(call->{exp.wasCanceled=true;return CompletableFuture.completedFuture(0);});})){
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   var actual=world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
   assertFalse(actual.isDone());leases.verifyNoInteractions();start.complete(true);assertTrue(actual.isDone());assertTrue(actual.join().wasCanceled);leases.verifyNoInteractions();packets.verifyNoInteractions();
  }
 }
 @Test void actualNativeFactoryAvoidsADuplicateFullLeaseAndRetainsWholeRegistryThroughTruePacketReceipts()throws Exception{
  var server=mock(MinecraftServer.class);var world=world(server);var start=new CompletableFuture<Boolean>();var core=new CompletableFuture<Integer>();var receipts=new CompletableFuture<Void>();
  try(var actors=mockStatic(ScarpetExplosionActors.class);var packets=mockStatic(ScarpetExplosionPackets.class);var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
      var constructed=mockConstruction(ServerExplosion.class,(exp,context)->{when(exp.carpetStartDecisionAsync()).thenReturn(start);when(exp.carpetExplodeAsync()).thenReturn(core);})){ 
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   packets.when(()->ScarpetExplosionPackets.send(any(),eq(17),any(),any(),any(),any())).thenAnswer(call->{assertNotNull(ScarpetNativeWork.capture());return receipts;});
   var actual=world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
   leases.verifyNoInteractions();start.complete(false);leases.verifyNoInteractions();var drain=ScarpetNativeWork.whenIdle(server);actual.cancel(false);assertFalse(drain.isDone());
   core.complete(17);assertFalse(drain.isDone());receipts.complete(null);assertTrue(drain.isDone());assertTrue(actual.isCancelled());leases.verifyNoInteractions();
  }
 }
}
