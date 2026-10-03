// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native predicates retain their original world and origin while each actual entity is evaluated by its owner. */
public final class ScarpetEntityPredicates {
    private ScarpetEntityPredicates() {}
    public static CompletableFuture<Boolean> matches(EntityPredicate predicate,ServerLevel originalWorld,Vec3 origin,Entity actualEntity) {
        if(actualEntity==null)return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> result=parts(predicate.carpetOrderedParts(),0,actualEntity,originalWorld,origin);
        ScarpetNativeWork.record(result);return result;
    }
    private static CompletableFuture<Boolean> parts(List<EntitySubPredicate> parts,int index,Entity entity,ServerLevel world,Vec3 origin) {
        if(index==parts.size())return CompletableFuture.completedFuture(true);
        Supplier<CompletableFuture<Boolean>> next=ScarpetRuntime.captureNativeContinuation(()->parts(parts,index+1,entity,world,origin));
        return part(parts.get(index),entity,world,origin).thenCompose(ScarpetRuntime.captureNativeFunction(passed->passed?next.get():CompletableFuture.completedFuture(false)));
    }
    private static CompletableFuture<Boolean> part(EntitySubPredicate predicate,Entity entity,ServerLevel world,Vec3 origin) {
        if(predicate instanceof EntityLocationPredicate location)return ScarpetLootConditions.actor(entity,entity::position).thenCompose(ScarpetRuntime.captureNativeFunction(position->location(location.predicate(),world,position)));
        if(predicate instanceof SteppingOnPredicate stepping)return ScarpetLootConditions.actor(entity,()->entity.onGround()?Vec3.atCenterOf(entity.getOnPos()):null)
            .thenCompose(ScarpetRuntime.captureNativeFunction(position->position==null?CompletableFuture.completedFuture(false):location(stepping.predicate(),world,position)));
        if(predicate instanceof MovementAffectedByPredicate movement)return ScarpetLootConditions.actor(entity,()->Vec3.atCenterOf(entity.getBlockPosBelowThatAffectsMyMovement()))
            .thenCompose(ScarpetRuntime.captureNativeFunction(position->location(movement.predicate(),world,position)));
        if(predicate instanceof VehiclePredicate vehicle)return ScarpetLootConditions.actor(entity,entity::getVehicle).thenCompose(ScarpetRuntime.captureNativeFunction(other->matches(vehicle.vehicle(),world,origin,other)));
        if(predicate instanceof PassengerPredicate passenger)return ScarpetLootConditions.actor(entity,()->List.copyOf(entity.getPassengers())).thenCompose(ScarpetRuntime.captureNativeFunction(all->any(passenger.passenger(),world,origin,all,0)));
        if(predicate instanceof TargetedEntityPredicate targeted)return ScarpetLootConditions.actor(entity,()->entity instanceof Mob mob?mob.getTarget():null).thenCompose(ScarpetRuntime.captureNativeFunction(other->matches(targeted.targetedEntity(),world,origin,other)));
        if(predicate instanceof LightningBoltPredicate lightning) {
            if(!(entity instanceof LightningBolt bolt))return CompletableFuture.completedFuture(false);
            return ScarpetLootConditions.actor(bolt,()->{
                if(!lightning.blocksSetOnFire().matches(bolt.getBlocksSetOnFire()))return null;
                return lightning.entityStruck().isEmpty()?List.<Entity>of():bolt.getHitEntities().toList();
            }).thenCompose(ScarpetRuntime.captureNativeFunction(all->all==null?CompletableFuture.completedFuture(false):lightning.entityStruck().isEmpty()?CompletableFuture.completedFuture(true):any(lightning.entityStruck().get(),world,origin,all,0)));
        }
        if(predicate instanceof EntityNbtPredicate nbt)return ScarpetPredicateNbt.tag(entity).thenApply(nbt.nbt()::matches);
        if(predicate instanceof PlayerPredicate player)return player(player,entity,world,origin);
        // All remaining registered vanilla parts read this actual entity, its immutable components or its own NBT, never caller-world blocks.
        return ScarpetLootConditions.actor(entity,()->predicate.matches(entity,world,origin));
    }
    private static CompletableFuture<Boolean> any(EntityPredicate predicate,ServerLevel world,Vec3 origin,List<Entity> entities,int index) {
        if(index==entities.size())return CompletableFuture.completedFuture(false);
        Supplier<CompletableFuture<Boolean>> next=ScarpetRuntime.captureNativeContinuation(()->any(predicate,world,origin,entities,index+1));
        return matches(predicate,world,origin,entities.get(index)).thenCompose(ScarpetRuntime.captureNativeFunction(passed->passed?CompletableFuture.completedFuture(true):next.get()));
    }
    private static CompletableFuture<Boolean> location(LocationPredicate predicate,ServerLevel originalWorld,Vec3 position) {
        return ScarpetLocationPredicates.matches(predicate,originalWorld,position);
    }
    private static CompletableFuture<Boolean> player(PlayerPredicate predicate,Entity entity,ServerLevel originalWorld,Vec3 origin) {
        if(!(entity instanceof ServerPlayer player))return CompletableFuture.completedFuture(false);
        // The original prefix executes level/food/gamemode/stats/recipes/advancements before looking_at, and input remains last.
        PlayerPredicate prefix=new PlayerPredicate(predicate.level(),predicate.food(),predicate.gameType(),predicate.stats(),predicate.recipes(),predicate.advancements(),Optional.empty(),Optional.empty());
        Supplier<CompletableFuture<Boolean>> look=ScarpetRuntime.captureNativeContinuation(()->predicate.lookingAt().isEmpty()?CompletableFuture.completedFuture(true):lookingAt(player,predicate.lookingAt().get()));
        Supplier<CompletableFuture<Boolean>> input=ScarpetRuntime.captureNativeContinuation(()->ScarpetLootConditions.actor(player,()->predicate.input().isEmpty()||predicate.input().get().matches(player.getLastClientInput())));
        return ScarpetLootConditions.actor(player,()->prefix.matches(player,originalWorld,origin)).thenCompose(ScarpetRuntime.captureNativeFunction(passed->passed?look.get().thenCompose(ScarpetRuntime.captureNativeFunction(matched->matched?input.get():CompletableFuture.completedFuture(false))):CompletableFuture.completedFuture(false)));
    }
    private record LookArea(ServerLevel world,BlockPos position) {}
    private record LookedAt(Entity entity,ServerLevel world,Vec3 origin) {}
    private static CompletableFuture<Boolean> lookingAt(ServerPlayer player,EntityPredicate predicate) {
        return ScarpetLootConditions.actor(player,()->new LookArea(player.level(),player.blockPosition().immutable())).thenCompose(ScarpetRuntime.captureNativeFunction(area->{
            int minX=(area.position().getX()-132)>>4,maxX=(area.position().getX()+132)>>4,minZ=(area.position().getZ()-132)>>4,maxZ=(area.position().getZ()+132)>>4;
            Supplier<CompletableFuture<Boolean>> evaluate=ScarpetRuntime.captureNativeContinuation(()->ScarpetLootConditions.actor(player,()->{
                BlockPos current=player.blockPosition();
                if(player.level()!=area.world()||((current.getX()-132)>>4)<minX||((current.getX()+132)>>4)>maxX||((current.getZ()-132)>>4)<minZ||((current.getZ()+132)>>4)>maxZ)return lookingAt(player,predicate);
                Vec3 from=player.getEyePosition(),view=player.getViewVector(1F),to=from.add(view.x*100,view.y*100,view.z*100);
                var hit=ProjectileUtil.getEntityHitResult(player.level(),player,from,to,new AABB(from,to).inflate(1),candidate->!candidate.isSpectator(),0F);
                if(hit==null||hit.getType()!=net.minecraft.world.phys.HitResult.Type.ENTITY)return CompletableFuture.completedFuture(false);
                LookedAt target=new LookedAt(hit.getEntity(),player.level(),player.position());
                Supplier<CompletableFuture<Boolean>> sight=ScarpetRuntime.captureNativeContinuation(()->lineOfSight(player,target.entity()));
                return matches(predicate,target.world(),target.origin(),target.entity()).thenCompose(ScarpetRuntime.captureNativeFunction(passed->passed?sight.get():CompletableFuture.completedFuture(false)));
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value)));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Boolean>>runValue(area.world(),minX,minZ,maxX,maxZ,lease->evaluate.get()).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        }));
    }
    private static CompletableFuture<Boolean> lineOfSight(ServerPlayer player,Entity target) {
        return ScarpetLootConditions.actor(target,()->new LookArea((ServerLevel)target.level(),target.blockPosition().immutable())).thenCompose(ScarpetRuntime.captureNativeFunction(other->ScarpetLootConditions.actor(player,()->{
            if(player.level()!=other.world())return CompletableFuture.completedFuture(false);
            BlockPos own=player.blockPosition();int minX=(Math.min(own.getX(),other.position().getX())-2)>>4,maxX=(Math.max(own.getX(),other.position().getX())+2)>>4,minZ=(Math.min(own.getZ(),other.position().getZ())-2)>>4,maxZ=(Math.max(own.getZ(),other.position().getZ())+2)>>4;
            var perform=ScarpetRuntime.captureNativeContinuation(()->ScarpetLootConditions.actor(player,()->{
                BlockPos now=player.blockPosition();
                if(player.level()!=other.world()||(now.getX()>>4)<minX||(now.getX()>>4)>maxX||(now.getZ()>>4)<minZ||(now.getZ()>>4)>maxZ)return lineOfSight(player,target);
                return ScarpetLootConditions.actor(target,()->{
                    BlockPos position=target.blockPosition();
                    if(target.level()!=other.world()||!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)||(position.getX()>>4)<minX||(position.getX()>>4)>maxX||(position.getZ()>>4)<minZ||(position.getZ()>>4)>maxZ)return lineOfSight(player,target);
                    return CompletableFuture.completedFuture(player.hasLineOfSight(target));
                }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value)));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Boolean>>runValue(other.world(),minX,minZ,maxX,maxZ,lease->perform.get()).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value))));
    }
}
