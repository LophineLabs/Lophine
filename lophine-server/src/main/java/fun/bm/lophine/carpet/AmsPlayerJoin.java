// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
/** A real player join retains the source extension order through all native children. */
public final class AmsPlayerJoin {
 private AmsPlayerJoin(){}
 public static CompletableFuture<Void> join(ServerPlayer player,boolean scarpet){
  return AmsNativeCommandEffects.nativeReceipt(player.carpetSpawnServer(),()->
   AmsNativeCommandEffects.then(AmsWelcomeMessage.sendAsync(player),ignored->
    AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player,()->{AmsManagementCommands.onJoin(player);return (Void)null;}),leader->
     AmsNativeCommandEffects.then(AmsRecipeLifecycle.loggedIn(player.carpetSpawnServer(),player),recipe->
      scarpet?AmsNativeCommandEffects.owned(player,()->carpet.script.external.ScarpetRuntime.onJoinFuture(player)).thenCompose(value->value):CompletableFuture.completedFuture(null)))));
 }
}