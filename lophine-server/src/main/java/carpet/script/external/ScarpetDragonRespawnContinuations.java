package carpet.script.external;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
/** Dragon animation time does not advance past its actual native explosion and feature placement. */
public final class ScarpetDragonRespawnContinuations {
 private static final WeakIdentityMap<EnderDragonFight,CompletableFuture<Void>> PENDING=new WeakIdentityMap<>();
 private ScarpetDragonRespawnContinuations(){}
 public static boolean pending(EnderDragonFight fight){var stage=PENDING.get(fight);return stage!=null&&!stage.isDone();}
 public static void beams(EnderDragonFight fight,ServerLevel world,java.util.List<net.minecraft.world.entity.boss.enderdragon.EndCrystal> crystals,BlockPos target,Runnable remaining){
  run(fight,world,new BlockPos(0,128,0),1,()->{
   var root=ScarpetNativeWork.capture();CompletableFuture<Void> sequence=CompletableFuture.completedFuture(null);
   for(var crystal:crystals)sequence=sequence.thenCompose(ignored->ScarpetNativeWork.with(root,()->
    ScarpetExplosionActors.entity(crystal,()->ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(crystal,()->{crystal.setBeamTarget(target);return null;}))).thenCompose(result->result)));
   ScarpetNativeWork.record(sequence);
  },remaining);
 }
 public static void run(EnderDragonFight fight,ServerLevel world,BlockPos center,int extent,Runnable prefix,Runnable remaining){
  if(pending(fight))return;var terminal=new CompletableFuture<Void>();PENDING.put(fight,terminal);
  var observed=ScarpetNativeWork.<CompletableFuture<Void>>observeNative(null,()->{
   var run=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionContinuations.world(world,center,prefix,remaining));
   var actual=fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(world,(center.getX()-extent)>>4,(center.getZ()-extent)>>4,
     (center.getX()+extent)>>4,(center.getZ()+extent)>>4,lease->run.get()).thenCompose(result->result);
   ScarpetNativeWork.record(actual);return actual;
  });
  ScarpetNativeWork.trackNative(world.getServer(),observed);
  var actual=ScarpetNativeWork.recoverGuestValue(observed).thenCompose(result->result);ScarpetNativeWork.aliasDependency(actual,observed);ScarpetNativeWork.trackNative(world.getServer(),actual)
   .whenComplete((done,failure)->{if(failure==null)terminal.complete(null);else{terminal.completeExceptionally(failure);net.minecraft.server.MinecraftServer.LOGGER.error("Native dragon respawn continuation failed",failure);}PENDING.remove(fight,terminal);});
 }
}
