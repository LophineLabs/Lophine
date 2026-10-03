// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Complete explosion observers retain the actual native caller's owner tail and dynamic children. */
public final class ScarpetExplosionContinuations {
 private ScarpetExplosionContinuations(){}
 private static final WeakIdentityMap<Entity,CompletableFuture<Void>> PENDING=new WeakIdentityMap<>();
 public static boolean pending(Entity owner){var future=PENDING.get(owner);return future!=null&&!future.isDone();}
 public static CompletableFuture<Void> runOnce(Entity owner,Runnable nativeExplosion,Runnable ownerTail){
  ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(owner,"Explosion caller must start on its actual owner");
  var existing=PENDING.get(owner);if(existing!=null&&!existing.isDone()){var view=existing.copy();ScarpetNativeWork.aliasDependency(view,existing);return view;}
  var terminal=new CompletableFuture<Void>();PENDING.put(owner,terminal);
  try{
   var actual=run(owner,nativeExplosion,ownerTail);ScarpetNativeWork.aliasDependency(terminal,actual);
   actual.whenComplete((ignored,failure)->{if(failure==null)terminal.complete(null);else{terminal.completeExceptionally(failure);net.minecraft.server.MinecraftServer.LOGGER.error("Native explosion caller continuation failed",failure);}PENDING.remove(owner,terminal);});
  }catch(Throwable failure){terminal.completeExceptionally(failure);PENDING.remove(owner,terminal);}
  var view=terminal.copy();ScarpetNativeWork.aliasDependency(view,terminal);return view;
 }
 /** Only a continuation of an already admitted target phase may use this helper. */
 public static <T> CompletableFuture<T> targetEffect(Entity target,Supplier<T> acceptedEffect){
  return ScarpetExplosionActors.entity(target,()->observe(target,()->{
   if(target instanceof net.minecraft.server.level.ServerPlayer player)
    try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){return acceptedEffect.get();}
   return acceptedEffect.get();
  })).thenCompose(result->result);
 }
 public static CompletableFuture<Void> phase(Entity owner,ScarpetNativeWork.Token root,Runnable operation){
  return ScarpetNativeWork.with(root,()->{
   var actual=ScarpetExplosionActors.entity(owner,()->ScarpetExplosionContinuations.<Void>observe(owner,()->{operation.run();return null;})).thenCompose(result->result);
   ScarpetNativeWork.record(actual);return actual;
  });
 }
 public static CompletableFuture<Void> run(Entity owner,Runnable nativeExplosion,Runnable ownerTail){
  return run(((ServerLevel)owner.level()).getServer(),owner,nativeExplosion,()->ScarpetExplosionActors.entity(owner,()->ScarpetExplosionContinuations.<Void>observe(owner,()->{ownerTail.run();return null;})).thenCompose(result->result));
 }
 public static CompletableFuture<Void> world(ServerLevel world,BlockPos position,Runnable nativeExplosion,Runnable ownerTail){
  return run(world.getServer(),null,nativeExplosion,()->ScarpetExplosionActors.world(world,position,()->ScarpetExplosionContinuations.<Void>observe(null,()->{ownerTail.run();return null;})).thenCompose(result->result));
 }
 private static <T> CompletableFuture<T> observe(Entity owner,Supplier<T> operation){
  return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(owner,operation));
 }
 private static CompletableFuture<Void> run(net.minecraft.server.MinecraftServer server,Entity owner,Runnable nativeExplosion,Supplier<CompletableFuture<Void>> ownerTail){
  var actual=ScarpetNativeWork.<Void>observeNative(owner,()->{
   var root=ScarpetNativeWork.capture();var tail=new CompletableFuture<Void>();ScarpetNativeWork.record(tail);
   var explosion=ScarpetNativeWork.<Void>observeNative(owner,()->{nativeExplosion.run();return null;});
   ScarpetNativeWork.recoverGuestValue(explosion).whenComplete((ignored,failure)->{
    if(failure!=null){tail.completeExceptionally(failure);return;}
    ScarpetNativeWork.with(root,()->{
     try{var continued=ownerTail.get();ScarpetNativeWork.record(continued);continued.whenComplete((done,error)->{if(error==null)tail.complete(null);else tail.completeExceptionally(error);});}
     catch(Throwable error){tail.completeExceptionally(error);}
    });
   });return null;
  });
  // Internal actual observer remains retained even if a caller cancels its return-value view.
  ScarpetNativeWork.trackNative(server,actual);
  var ready=ScarpetNativeWork.recoverGuestValue(actual);var view=ready.copy();ScarpetNativeWork.aliasDependency(view,actual);return view;
 }
}
