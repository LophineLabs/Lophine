// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.entity.boss.enderdragon.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.*;
import net.minecraft.world.phys.*;
import net.minecraft.sounds.*;

/** Native attack HEAD reads and mutations run on the actual source and recipient actors in source order. */
public final class ScarpetNativeAttackHeads {
    private ScarpetNativeAttackHeads(){}
    public record Eligibility(boolean allowed,org.bukkit.entity.Entity api){}
    public record OldDamage(float health,Vec3 movement){}
    private record Aim(Vec3 look,EntityReference<Entity> source){}
    public static CompletableFuture<Eligibility> eligibility(Player source,Entity target){
        return ScarpetNativeDeathActors.entity(target,()->new Eligibility(target.isAttackable()&&!target.skipAttackInteraction(source),target.getBukkitEntity()));
    }
    public static CompletableFuture<OldDamage> beforeDamage(Entity target){
        return ScarpetNativeDeathActors.entity(target,()->new OldDamage(target instanceof LivingEntity living?living.getHealth():0F,target.getDeltaMovement()));
    }
    /** Vanilla hurtOrSimulate selects the recipient's actual world at this exact stage. */
    public static CompletableFuture<Boolean> hurt(Entity target,DamageSource source,float damage){
        return ScarpetNativeDeathActors.entity(target,()->target.level() instanceof ServerLevel world?ScarpetExplosionActors.hurt(target,world,source,damage):
            CompletableFuture.completedFuture(target.hurtOrSimulate(source,damage))).thenCompose(value->value);
    }
    /** Preserve the actual projectile/source identities, while AIM's sole source read runs on its source actor. */
    public static CompletableFuture<Boolean> deflect(Player source,Entity target,DamageSource damage,float magic){
        return ScarpetNativeDeathActors.entity(target,()->target.is(EntityTypeTags.REDIRECTABLE_PROJECTILE)&&target instanceof Projectile)
            .thenCompose(ScarpetRuntime.captureNativeFunction(projectile->{
                if(!projectile)return CompletableFuture.completedFuture(false);
                return ScarpetNativeDeathActors.entity(target,()->!org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(target,damage,magic,false))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(allowed->{
                        if(!allowed)return CompletableFuture.completedFuture(false);
                        return ScarpetNativeDeathActors.entity(source,()->new Aim(source.getLookAngle(),EntityReference.of(source)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(aim->ScarpetNativeDeathActors.entity(target,()->{
                                ProjectileDeflection ownedAim=(actual,actualSource,random,power)->{actual.setDeltaMovement(aim.look().multiply(power));actual.needsSync=true;};
                                return ((Projectile)target).deflect(ownedAim,source,aim.source(),true,1D);
                            })));
                    }));
            }));
    }
    public static CompletableFuture<Boolean> creative(Player player,Entity target){
        return ScarpetNativeDeathActors.target(player,()->{
            boolean creative=player.isCreative();
            if(creative&&player instanceof ServerPlayer&&fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeHitRemoveEntity)return 1;
            return 0;
        }).thenCompose(ScarpetRuntime.captureNativeFunction(mode->{
            if(mode==0)return CompletableFuture.completedFuture(false);
            return removeHit(player,target).thenApply(ignored->true);
        }));
    }
    /** The AMS injection is at the real attack HEAD and continues into the original attack body. */
    public static CompletableFuture<Void> creativeAMS(Player player,Entity target){
        return ScarpetNativeDeathActors.target(player,()->fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOneHitKill
            &&player.level() instanceof ServerLevel&&player.getAbilities().instabuild)
            .thenCompose(ScarpetRuntime.captureNativeFunction(enabled->{
                if(!enabled)return CompletableFuture.completedFuture(null);
                return ScarpetNativeDeathActors.entity(target,()->EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(allowed->{
                        if(!allowed)return CompletableFuture.completedFuture(null);
                        return ScarpetNativeDeathActors.target(player,()->new CreativeWorld((ServerLevel)player.level(),player.isShiftKeyDown()))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(source->{
                                if(!source.sweep())return instantAMS(player,target);
                                return ScarpetNativeDeathActors.entity(target,()->target.getBoundingBox().inflate(2D,.5D,2D))
                                    .thenCompose(ScarpetRuntime.captureNativeFunction(area->ScarpetNativeAttackBodies.worldArea(source.world(),area,player,
                                        ()->List.copyOf(source.world().getEntitiesOfClass(Entity.class,area)))))
                                    .thenCompose(ScarpetRuntime.captureNativeFunction(all->killNextAMS(player,all.iterator())))
                                    .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->sound(player,SoundEvents.PLAYER_ATTACK_SWEEP)));
                            }));
                    }));
            }));
    }
    private record CreativeWorld(ServerLevel world,boolean sweep){}
    private static CompletableFuture<Void> instantAMS(Player player,Entity target){
        return killAMS(target)
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->sound(player,SoundEvents.PLAYER_ATTACK_CRIT)));
    }
    private static CompletableFuture<Void> killNextAMS(Player player,Iterator<Entity> all){
        if(!all.hasNext())return CompletableFuture.completedFuture(null);Entity next=all.next();
        return ScarpetNativeDeathActors.entity(next,()->next.isAttackable()&&EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(next))
            .thenCompose(ScarpetRuntime.captureNativeFunction(allowed->allowed?instantAMS(player,next):CompletableFuture.<Void>completedFuture(null)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->killNextAMS(player,all)));
    }
    private static CompletableFuture<Void> killAMS(Entity target){
        if(target instanceof EnderDragonPart part)return ScarpetNativeDeathActors.entity(part.parentMob,()->part.parentMob.getSubEntities().length)
            .thenCompose(ScarpetRuntime.captureNativeFunction(count->killRepeatedAMS(target,count)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->ScarpetNativeDeathActors.entity(target,()->(ServerLevel)target.level())))
            .thenCompose(ScarpetRuntime.captureNativeFunction(world->ScarpetNativeDeathActors.entity(part.parentMob,()->{part.parentMob.kill(world);return (Void)null;})));
        return ScarpetNativeDeathActors.entity(target,()->{target.kill((ServerLevel)target.level());return (Void)null;});
    }
    private static CompletableFuture<Void> killRepeatedAMS(Entity actualTarget,int remaining){
        if(remaining==0)return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(actualTarget,()->{actualTarget.kill((ServerLevel)actualTarget.level());return (Void)null;})
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->killRepeatedAMS(actualTarget,remaining-1)));
    }
    private static CompletableFuture<Void> sound(Player player,SoundEvent sound){
        return ScarpetNativeDeathActors.target(player,()->{player.carpetPlayServerSideSound(sound);return (Void)null;});
    }
    private static CompletableFuture<Void> remove(Entity target){
        return ScarpetNativeDeathActors.entity(target,()->{
            if(target instanceof Player||!EntitySelector.NO_SPECTATORS.test(target)||target.isRemoved())return (List<Entity>)null;
            return target instanceof EnderDragon dragon?List.<Entity>copyOf(Arrays.asList(dragon.getSubEntities())):List.<Entity>of();
        }).thenCompose(ScarpetRuntime.captureNativeFunction(parts->parts==null?CompletableFuture.<Void>completedFuture(null):
            removeParts(parts.iterator()).thenCompose(ScarpetRuntime.captureNativeFunction(ignored->ScarpetNativeDeathActors.entity(target,()->{target.carpetDiscardCleanly();return (Void)null;})))));
    }
    private static CompletableFuture<Void> removeParts(Iterator<Entity> parts){
        if(!parts.hasNext())return CompletableFuture.completedFuture(null);Entity part=parts.next();
        return ScarpetNativeDeathActors.entity(part,()->{part.carpetDiscardCleanly();return (Void)null;})
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->removeParts(parts)));
    }
    private record RemovalSound(double x,double y,double z,SoundSource source){}
    private static CompletableFuture<Void> removeHit(Player player,Entity target){
        return ScarpetNativeDeathActors.entity(player,()->{
            ServerLevel original=(ServerLevel)player.level();
            boolean sword=player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).is(ItemTags.SWORDS);
            boolean onGround=player.onGround();boolean sweep=sword&&onGround&&!player.isSprinting();
            List<Entity> selected=new ArrayList<>();selected.add(target);
            var prepared=sweep?ScarpetNativeDeathActors.entity(target,()->target.getBoundingBox().inflate(1D,.25D,1D))
                .thenCompose(ScarpetRuntime.captureNativeFunction(area->ScarpetNativeAttackBodies.worldArea(original,area,player,()->List.copyOf(original.getEntitiesOfClass(Entity.class,area)))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(all->selectRemovals(player,target,all.iterator(),selected))):CompletableFuture.completedFuture(selected);
            return prepared.thenCompose(ScarpetRuntime.captureNativeFunction(ScarpetNativeEntityRemoval::removeAll))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->ScarpetNativeDeathActors.entity(player,()->
                    new RemovalSound(player.getX(),player.getY(),player.getZ(),player.getSoundSource()))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(sound->ScarpetExplosionActors.world(original,net.minecraft.core.BlockPos.containing(sound.x(),sound.y(),sound.z()),()->
                    ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(null,()->{
                        original.playSound(null,sound.x(),sound.y(),sound.z(),sweep?SoundEvents.PLAYER_ATTACK_SWEEP:SoundEvents.PLAYER_ATTACK_STRONG,sound.source(),.7F,.9F);return (Void)null;
                    }))).thenCompose(value->value)));
        }).thenCompose(value->value);
    }
    private static CompletableFuture<List<Entity>> selectRemovals(Player player,Entity primary,Iterator<Entity> all,List<Entity> selected){
        if(!all.hasNext())return CompletableFuture.completedFuture(selected);Entity next=all.next();
        if(next==player||next==primary)return selectRemovals(player,primary,all,selected);
        return ScarpetNativeDeathActors.entity(next,()->ScarpetNativeEntityRemoval.canRemove(next)?next.position():null)
            .thenCompose(ScarpetRuntime.captureNativeFunction(position->ScarpetNativeDeathActors.entity(player,()->position!=null&&player.position().distanceToSqr(position)<9D)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(allowed->{if(allowed)selected.add(next);return selectRemovals(player,primary,all,selected);}));
    }
}
