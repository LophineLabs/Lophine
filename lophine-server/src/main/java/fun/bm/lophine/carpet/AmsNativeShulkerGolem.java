// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet AMS Addition shulkerGolem, revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** The original world, original summoned entity and each real world child remain in source order. */
public final class AmsNativeShulkerGolem {
 private AmsNativeShulkerGolem(){}
 public static CompletableFuture<Void> spawn(ServerLevel world,BlockPos head){
  BlockPos original=head.immutable(),body=original.below();
  return AmsNativeCommandEffects.nativeReceipt(world.getServer(),()->AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(world,original,()->{
   boolean pumpkin=world.getBlockState(original).getBlock() instanceof CarvedPumpkinBlock;
   boolean box=world.getBlockState(body).getBlock() instanceof ShulkerBoxBlock;
   if(!pumpkin||!box)return null;
   Shulker actual=Objects.requireNonNull(EntityTypes.SHULKER.create(world,EntitySpawnReason.MOB_SUMMONED));
   actual.snapTo(body.getX()+.5D,body.getY(),body.getZ()+.5D,0F,0F);return actual;
  }),actual->actual==null?CompletableFuture.completedFuture(null):
   AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(world,body,()->{world.destroyBlock(body,false);return (Void)null;}),ignored->
    AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(world,original,()->{world.destroyBlock(original,false);return (Void)null;}),destroyed->
     AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(world,body,()->{world.addFreshEntity(actual);return (Void)null;}),added->
      AmsNativeCommandEffects.world(world,body,()->{
       world.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH,0),body.getX()+.5D,body.getY(),body.getZ()+.5D,1688,.8D,.8D,.8D,.0168D);return (Void)null;
      }))))));
 }
}
