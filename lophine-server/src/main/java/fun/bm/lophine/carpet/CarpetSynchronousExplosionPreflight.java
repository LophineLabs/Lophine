package fun.bm.lophine.carpet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.EntityEventsGroup;
import carpet.script.CarpetEventServer.Event;
import carpet.script.external.*;
/** Only already loaded, fully owned native work can satisfy Bukkit's synchronous Boolean contract. */
public final class CarpetSynchronousExplosionPreflight{
 private CarpetSynchronousExplosionPreflight(){}
 private static void reject(String reason){throw CarpetSynchronousExplosionScope.unavailable(reason);}
 public static double packetRange(){double range=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange;return range>=0?range:64D;}
 public static void require(ServerLevel world,Entity source,net.minecraft.world.damagesource.DamageSource damage,ExplosionDamageCalculator calculator,Vec3 center,float power){
  if(!Double.isFinite(center.x)||!Double.isFinite(center.y)||!Double.isFinite(center.z)||!Float.isFinite(power)||power<0F)reject("Explosion coordinates and nonnegative power must be finite");
  if(ScarpetNativeWork.isDraining(world.getServer())&&ScarpetNativeWork.capture()==null)reject("The server is draining previously accepted native operations");
  if(!ScarpetRuntime.EVENT_DISABLED.get()&&(Event.EXPLOSION.isNeeded()||Event.EXPLOSION_OUTCOME.isNeeded()||Event.PLAYER_TAKES_DAMAGE.isNeeded()||Event.PLAYER_DEALS_DAMAGE.isNeeded()||Event.PLAYER_DIES.isNeeded()))reject("Scarpet explosion, damage or death callbacks require asynchronous owner continuations");
  if(calculator!=null&&calculator.getClass()!=ExplosionDamageCalculator.class&&calculator.getClass()!=SimpleExplosionDamageCalculator.class&&calculator.getClass()!=EntityBasedExplosionDamageCalculator.class)reject("A custom explosion calculator requires the asynchronous interface");
  double physical=Math.ceil(power*2D)+2D, extent=Math.max(physical,packetRange());
  requireRectangle(world,center,extent,physical);
  if(source!=null){requireEntity(world,source);LivingEntity indirect=Explosion.getIndirectSourceEntity(source);if(indirect!=null)requireEntity(world,indirect);}
  if(damage!=null){Entity direct=damage.getDirectEntity(),causing=damage.getEntity();if(direct!=null)requireEntity(world,direct);if(causing!=null)requireEntity(world,causing);}
  AABB affected=new AABB(center.x-physical,center.y-physical,center.z-physical,center.x+physical,center.y+physical,center.z+physical);
  for(Entity target:world.getEntities((Entity)null,affected,entity->true))requireEntity(world,target);
  // Complete ownership of the packet rectangle puts every eligible recipient in this region's live audience.
  for(ServerPlayer player:List.copyOf(world.getLocalPlayers())){
   if(!TickThread.isTickThreadFor(player))reject("A local packet recipient is changing native owner");
   if(player.level()==world&&player.distanceToSqr(center)<packetRange()*packetRange())requireEntity(world,player);
  }
 }
 private static void requireEntity(ServerLevel world,Entity entity){
  if(!TickThread.isTickThreadFor(entity))reject("An explosion source, indirect cause or target is on another native owner");
  if(entity.level()!=world)reject("An explosion source, indirect cause or target changed world");
  if(ScarpetNativeRemovals.isPending(entity)||ScarpetDamageContinuations.pendingCompletion(entity)!=null)reject("A target has a pending native damage or removal operation");
  var group=entity.carpetPeekEventContainer();
  if(group!=null&&(group.hasEvent(EntityEventsGroup.Event.ON_DAMAGE)||group.hasEvent(EntityEventsGroup.Event.ON_DEATH)||group.hasEvent(EntityEventsGroup.Event.ON_REMOVED)))reject("A target has asynchronous entity damage, death or removal callbacks");
  if(entity instanceof ServerPlayer player){
   if(ScarpetPlayerInventoryGate.paused(player))reject("A target player inventory is held by another operation");
   var inventory=player.getInventory();for(int slot=0;slot<inventory.getContainerSize();slot++)if(OrgItemShadowGroups.managed(inventory.getItem(slot)))reject("A player inventory has a shared item scope");
  }
  if(entity instanceof LivingEntity living){
   if(living.isDeadOrDying())reject("A target is already in a native death phase");
   for(EquipmentSlot slot:EquipmentSlot.VALUES_ARRAY)if(OrgItemShadowGroups.managed(living.getItemBySlot(slot)))reject("Target equipment has a shared item scope");
  }
  if(entity instanceof net.minecraft.world.entity.item.ItemEntity item&&OrgItemShadowGroups.managed(item.getItem()))reject("A target item has a shared item scope");
 }
 private static void requireRectangle(ServerLevel world,Vec3 center,double extent,double physical){
  double minXd=Math.floor((center.x-extent)/16D),maxXd=Math.floor((center.x+extent)/16D),minZd=Math.floor((center.z-extent)/16D),maxZd=Math.floor((center.z+extent)/16D);
  if(minXd<Integer.MIN_VALUE||maxXd>Integer.MAX_VALUE||minZd<Integer.MIN_VALUE||maxZd>Integer.MAX_VALUE)reject("The complete explosion footprint cannot be represented as a native chunk rectangle");
  for(long z=(long)minZd;z<=(long)maxZd;z++)for(long x=(long)minXd;x<=(long)maxXd;x++){
   if(!TickThread.isTickThreadFor(world,(int)x,(int)z))reject("The complete explosion and packet footprint is not on the current native owner");
   var chunk=world.getChunkIfLoaded((int)x,(int)z);if(chunk==null)reject("The complete explosion footprint is not already loaded");
   for(var position:chunk.getBlockEntities().keySet())if(Math.abs(position.getX()-center.x)<=physical&&Math.abs(position.getY()-center.y)<=physical&&Math.abs(position.getZ()-center.z)<=physical)
    reject("A block entity may require an asynchronous inventory or loot scope");
  }
 }
 public static void requireBlocks(ServerLevel world,List<net.minecraft.core.BlockPos> blocks){
  for(var position:blocks){
   if(!TickThread.isTickThreadFor(world,position))reject("A Bukkit explosion callback added a block on another native owner");
   var chunk=world.getChunkIfLoaded(position.getX()>>4,position.getZ()>>4);if(chunk==null)reject("A Bukkit explosion callback added a block outside the loaded footprint");
   if(chunk.getBlockEntities().containsKey(position))reject("A Bukkit explosion callback added a block entity with an unproved synchronous inventory scope");
  }
 }
}
