package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetDragonExplosionContinuationTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualFightWindowRetainsFullLeaseThroughExplosionOwnerFeatureAndDynamicChildren(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var fight=mock(EnderDragonFight.class);var boom=new CompletableFuture<Void>();var feature=new CompletableFuture<Void>();var held=new java.util.concurrent.atomic.AtomicBoolean();var order=new ArrayList<String>();
  try(var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();var actors=mockStatic(ScarpetExplosionActors.class)){
   leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{held.set(true);var child=(CompletableFuture<?>)((Function<?,?>)call.getArgument(5)).apply(null);child.whenComplete((done,failure)->held.set(false));return CompletableFuture.completedFuture(child);});
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   ScarpetDragonRespawnContinuations.run(fight,world,BlockPos.ZERO,14,()->{order.add("actual explosion");ScarpetNativeWork.record(boom);},()->{assertTrue(held.get());order.add("actual feature owner");ScarpetNativeWork.record(feature);});
   assertTrue(ScarpetDragonRespawnContinuations.pending(fight));assertTrue(held.get());assertEquals(List.of("actual explosion"),order);var drain=ScarpetNativeWork.whenIdle(server);assertFalse(drain.isDone());
   ScarpetDragonRespawnContinuations.run(fight,world,BlockPos.ZERO,14,()->fail("duplicate stage"),()->{});
   boom.complete(null);assertEquals(List.of("actual explosion","actual feature owner"),order);assertTrue(held.get());assertFalse(drain.isDone());feature.complete(null);
   assertFalse(held.get());assertFalse(ScarpetDragonRespawnContinuations.pending(fight));assertTrue(drain.isDone());
  }
 }
 @Test void actualSummonStageRunsEachCrystalExplosionAndDiscardSequentially(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var fight=mock(EnderDragonFight.class);var a=mock(EndCrystal.class);var b=mock(EndCrystal.class);when(a.level()).thenReturn(world);when(b.level()).thenReturn(world);
  var boomA=new CompletableFuture<Void>();var boomB=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("A explosion");ScarpetNativeWork.record(boomA);return null;}).when(world).explode(eq(a),anyDouble(),anyDouble(),anyDouble(),eq(6F),eq(net.minecraft.world.level.Level.ExplosionInteraction.NONE));
  doAnswer(call->{order.add("B explosion");ScarpetNativeWork.record(boomB);return null;}).when(world).explode(eq(b),anyDouble(),anyDouble(),anyDouble(),eq(6F),eq(net.minecraft.world.level.Level.ExplosionInteraction.NONE));
  doAnswer(call->{order.add("A actual discard");return null;}).when(a).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE);
  doAnswer(call->{order.add("B actual discard");return null;}).when(b).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE);
  try(var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();var actors=mockStatic(ScarpetExplosionActors.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   actors.when(()->ScarpetExplosionActors.entity(any(Entity.class),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   DragonRespawnStage.SUMMONING_DRAGON.tick(world,fight,List.of(a,b),100);assertEquals(List.of("A explosion"),order);assertTrue(ScarpetDragonRespawnContinuations.pending(fight));verify(b,never()).setBeamTarget(any());
   boomA.complete(null);assertEquals(List.of("A explosion","A actual discard","B explosion"),order);assertTrue(ScarpetDragonRespawnContinuations.pending(fight));boomB.complete(null);
   assertEquals(List.of("A explosion","A actual discard","B explosion","B actual discard"),order);assertFalse(ScarpetDragonRespawnContinuations.pending(fight));verify(fight).setRespawnStage(DragonRespawnStage.END);verify(fight).resetSpikeCrystals();
  }
 }
 @Test void actualFailureDoesNotPlaceFeatureButFinishesTheNativeWindow(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var fight=mock(EnderDragonFight.class);var boom=new CompletableFuture<Void>();
  try(var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()){
   leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
   ScarpetDragonRespawnContinuations.run(fight,world,BlockPos.ZERO,14,()->ScarpetNativeWork.record(boom),()->fail("feature cannot run after failed explosion"));assertTrue(ScarpetDragonRespawnContinuations.pending(fight));
   boom.completeExceptionally(new IllegalStateException("actual failure"));assertFalse(ScarpetDragonRespawnContinuations.pending(fight));assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
 @Test void actualStartBeamWaitsRealCrystalOwnerAndNativeChildBeforeStageTransition(){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var fight=mock(EnderDragonFight.class);var crystal=mock(EndCrystal.class);var child=new CompletableFuture<Void>();
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(crystal).setBeamTarget(new BlockPos(0,128,0));
  try(var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();var actors=mockStatic(ScarpetExplosionActors.class)){
   leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
   actors.when(()->ScarpetExplosionActors.world(eq(world),any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()));
   actors.when(()->ScarpetExplosionActors.entity(eq(crystal),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   DragonRespawnStage.START.tick(world,fight,List.of(crystal),0);verify(fight,never()).setRespawnStage(any());assertTrue(ScarpetDragonRespawnContinuations.pending(fight));child.complete(null);verify(fight).setRespawnStage(DragonRespawnStage.PREPARING_TO_SUMMON_PILLARS);assertFalse(ScarpetDragonRespawnContinuations.pending(fight));
  }
 }
}
