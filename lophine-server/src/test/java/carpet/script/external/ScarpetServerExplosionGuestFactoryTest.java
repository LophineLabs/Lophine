package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.core.particles.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetServerExplosionGuestFactoryTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 @Test void actualFactoryWaitsConfiguratorChildrenThenTrueCoreCountPacketsAndPreservesFailedOriginalReceipt() throws Exception{
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class,CALLS_REAL_METHODS);var field=ServerLevel.class.getDeclaredField("server");field.setAccessible(true);field.set(world,server);var guest=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();var packets=new CompletableFuture<Void>();var phases=new ArrayList<String>();var result=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<ServerExplosion>>();
  try(var actors=mockStatic(ScarpetExplosionActors.class);var sent=mockStatic(ScarpetExplosionPackets.class);var leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);var constructor=mockConstruction(ServerExplosion.class,(exp,context)->{when(exp.carpetStartDecisionAsync()).thenAnswer(c->{phases.add("actual start");return CompletableFuture.completedFuture(false);});when(exp.carpetExplodeAsync()).thenAnswer(c->{phases.add("actual core count");return CompletableFuture.completedFuture(7);});})){
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
   sent.when(()->ScarpetExplosionPackets.send(any(),eq(7),any(),any(),any(),any())).thenAnswer(c->{phases.add("actual packet");return packets;});
   var original=ScarpetNativeWork.observeNative(null,()->{result.set(world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,exp->{phases.add("actual configurator");ScarpetNativeWork.record(guest);ScarpetNativeWork.record(late);}));return null;});
   var failure=new IllegalStateException("guest configurator callback");ScarpetNativeWork.markGuestFailure(failure);guest.completeExceptionally(failure);assertEquals(List.of("actual configurator"),phases);late.complete(null);assertEquals(List.of("actual configurator","actual start","actual core count","actual packet"),phases);assertFalse(result.get().isDone());packets.complete(null);assertSame(constructor.constructed().getFirst(),result.get().join());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
 @Test void realConfiguratorFailureCreatesNoStartOrPacketTail() throws Exception{
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class,CALLS_REAL_METHODS);var field=ServerLevel.class.getDeclaredField("server");field.setAccessible(true);field.set(world,server);
  try(var actors=mockStatic(ScarpetExplosionActors.class);var packets=mockStatic(ScarpetExplosionPackets.class);var constructor=mockConstruction(ServerExplosion.class)){
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   var actual=world.explode0Async(null,null,null,0,0,0,4,false,Level.ExplosionInteraction.NONE,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,exp->{throw new IllegalStateException("actual configurator failure");});assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));verify(constructor.constructed().getFirst(),never()).carpetStartDecisionAsync();packets.verifyNoInteractions();
  }
 }
}
