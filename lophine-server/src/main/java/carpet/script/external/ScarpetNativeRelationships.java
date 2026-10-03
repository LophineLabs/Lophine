// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;

/** Split Native dismount uses the original rider and vehicle even across worlds or final retired owners. */
public final class ScarpetNativeRelationships {
    private ScarpetNativeRelationships() {}
    private record Vehicle(CraftEntity craft,boolean valid) {}
    private record Location(ServerLevel world,BlockPos position) {}
    public static CompletableFuture<Void> stopRiding(Entity rider,boolean suppressCancellation) {
        return ScarpetExplosionActors.entity(rider,rider::getVehicle).thenCompose(vehicle->{
            if(vehicle==null)return ScarpetExplosionActors.entity(rider,()->ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(rider,()->{rider.stopRiding(suppressCancellation);return null;}))).thenCompose(value->value);
            var source=ScarpetExplosionActors.entity(rider,()->new Location((ServerLevel)rider.level(),rider.blockPosition().immutable()));
            var other=ScarpetExplosionActors.entity(vehicle,()->new Location((ServerLevel)vehicle.level(),vehicle.blockPosition().immutable()));
            return source.thenCombine(other,(from,to)->{
                if(from.world()!=to.world())return splitStopRiding(rider,suppressCancellation);
                int minX=(Math.min(from.position().getX(),to.position().getX())-32)>>4,maxX=(Math.max(from.position().getX(),to.position().getX())+32)>>4;
                int minZ=(Math.min(from.position().getZ(),to.position().getZ())-32)>>4,maxZ=(Math.max(from.position().getZ(),to.position().getZ())+32)>>4;
                var original=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(rider,()->{
                    if(rider.getVehicle()!=vehicle)return stopRiding(rider,suppressCancellation);
                    BlockPos now=rider.blockPosition();
                    if(rider.level()!=from.world() || (now.getX()>>4)<minX || (now.getX()>>4)>maxX || (now.getZ()>>4)<minZ || (now.getZ()>>4)>maxZ)return stopRiding(rider,suppressCancellation);
                    return ScarpetExplosionActors.entity(vehicle,()->{
                        BlockPos moved=vehicle.blockPosition();
                        if(vehicle.level()!=from.world() || (moved.getX()>>4)<minX || (moved.getX()>>4)>maxX || (moved.getZ()>>4)<minZ || (moved.getZ()>>4)>maxZ)return stopRiding(rider,suppressCancellation);
                        return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(rider,()->{rider.stopRiding(suppressCancellation);return null;}));
                    }).thenCompose(value->value);
                }).thenCompose(value->value));
                return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(from.world(),minX,minZ,maxX,maxZ,lease->original.get()).thenCompose(value->value);
            }).thenCompose(value->value);
        });
    }
    private static CompletableFuture<Void> splitStopRiding(Entity rider,boolean suppressCancellation) {
        var whole=ScarpetNativeWork.observeNative(rider,()->{
            var phase=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(rider,rider::carpetBeginVehicleRemoval).thenCompose(vehicle->{
                if(vehicle==null)return ScarpetExplosionActors.entity(rider,()->ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(rider,()->{rider.stopRiding(suppressCancellation);return null;}))).thenCompose(value->value);
                var events=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(vehicle,()->new Vehicle(vehicle.getBukkitEntity(),vehicle.valid))
                    .thenCompose(view->{
                        if(!view.valid())return CompletableFuture.completedFuture(true);
                        var entityExit=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(vehicle,()->vehicle.valid)
                            .thenCompose(currentValid->ScarpetExplosionActors.entity(rider,()->rider.carpetEntityDismountEvent(view.craft(),currentValid,suppressCancellation))));
                        return ScarpetExplosionActors.entity(rider,()->rider.carpetDismountEvents(vehicle,view.craft(),true,suppressCancellation))
                            .thenCompose(accepted->accepted?entityExit.get():CompletableFuture.completedFuture(false));
                    }));
                var finish=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(vehicle,vehicle::position)
                    .thenCompose(position->finish(rider,vehicle,false,position)));
                var physical=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(vehicle,()->{vehicle.carpetRemovePassengerPhysical(rider);return null;})
                    .thenCompose(ignored->ScarpetExplosionActors.entity(vehicle,vehicle::position))
                    .thenCompose(position->finish(rider,vehicle,true,position)));
                return events.get().thenCompose(removed->removed?physical.get():finish.get()).thenCompose(ignored->virtualTails(rider,vehicle));
            }));
            var actual=phase.get();ScarpetNativeWork.record(actual);return actual;
        });
        var actual=ScarpetNativeWork.recoverGuestValue(whole).thenCompose(value->value);ScarpetNativeWork.aliasDependency(actual,whole);ScarpetNativeWork.record(actual);return actual;
    }
    private static CompletableFuture<Void> finish(Entity rider,Entity vehicle,boolean removed,Vec3 currentVehiclePosition) {
        return ScarpetExplosionActors.entity(rider,()->{
            ServerLevel world=(ServerLevel)rider.level();BlockPos origin=BlockPos.containing(currentVehiclePosition);BlockPos own=rider.blockPosition();
            int minX=Math.min((origin.getX()-16)>>4,own.getX()>>4),maxX=Math.max((origin.getX()+16)>>4,own.getX()>>4);
            int minZ=Math.min((origin.getZ()-16)>>4,own.getZ()>>4),maxZ=Math.max((origin.getZ()+16)>>4,own.getZ()>>4);
            var perform=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(rider,()->{
                BlockPos now=rider.blockPosition();
                if(rider.level()!=world || (now.getX()>>4)<minX || (now.getX()>>4)>maxX || (now.getZ()>>4)<minZ || (now.getZ()>>4)>maxZ)
                    return finish(rider,vehicle,removed,currentVehiclePosition);
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(rider,()->{rider.carpetCompleteVehicleRemoval(vehicle,removed,currentVehiclePosition);return null;}));
            }).thenCompose(value->value));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(world,minX,minZ,maxX,maxZ,lease->perform.get()).thenCompose(value->value);
        }).thenCompose(value->value);
    }

    private record PacketSource(int id,java.util.List<Entity> passengers) {}
    private record VehicleState(ServerLevel world,BlockPos block,Vec3 position,boolean removed,boolean valid) {}
    /** Exact original override order: Player cooldown, SP effect/passenger packets, then Living.stopRiding position. */
    private static CompletableFuture<Void> virtualTails(Entity rider,Entity vehicle) {
        var packets=ScarpetRuntime.captureNativeContinuation(()->rider instanceof net.minecraft.server.level.ServerPlayer player?vehiclePackets(player,vehicle):CompletableFuture.<Void>completedFuture(null));
        var living=ScarpetRuntime.captureNativeContinuation(()->rider instanceof net.minecraft.world.entity.LivingEntity entity?livingDismount(entity,vehicle):CompletableFuture.<Void>completedFuture(null));
        return ScarpetExplosionActors.entity(rider,()->{
            if(rider instanceof net.minecraft.world.entity.player.Player player)player.carpetFinishRemoveVehicle();return null;
        }).thenCompose(ignored->packets.get()).thenCompose(ignored->living.get());
    }
    private static CompletableFuture<Void> vehiclePackets(net.minecraft.server.level.ServerPlayer rider,Entity vehicle) {
        var passengers=ScarpetRuntime.captureNativeContinuation(()->passengerPacket(rider,vehicle));
        return ScarpetExplosionActors.entity(vehicle,()->{
            java.util.List<net.minecraft.network.protocol.Packet<?>> result=new java.util.ArrayList<>();
            if(vehicle instanceof net.minecraft.world.entity.LivingEntity living)for(var effect:living.getActiveEffects())
                result.add(new net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket(vehicle.getId(),effect.getEffect()));
            return result;
        }).thenCompose(packets->ScarpetNativeDeathEffects.sequence(rider,packets.iterator(),packet->rider.connection.send(packet),()->{})).thenCompose(ignored->passengers.get());
    }
    private static CompletableFuture<Void> passengerPacket(net.minecraft.server.level.ServerPlayer rider,Entity vehicle) {
        return ScarpetExplosionActors.entity(vehicle,()->new PacketSource(vehicle.getId(),java.util.List.copyOf(vehicle.getPassengers()))).thenCompose(source->{
            CompletableFuture<java.util.List<Integer>> identifiers=CompletableFuture.completedFuture(new java.util.ArrayList<>());
            for(Entity passenger:source.passengers()) {
                var next=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(passenger,passenger::getId));
                identifiers=identifiers.thenCompose(ids->next.get().thenApply(id->{ids.add(id);return ids;}));
            }
            var packet=ScarpetRuntime.<java.util.List<Integer>,CompletableFuture<Void>>captureNativeFunction(ids->
                ScarpetNativeDeathEffects.sequence(rider,java.util.List.<net.minecraft.network.protocol.Packet<?>>of(
                    new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(source.id(),ids.stream().mapToInt(Integer::intValue).toArray())).iterator(),value->rider.connection.send(value),()->{}));
            return identifiers.thenCompose(packet);
        });
    }
    private static CompletableFuture<Void> livingDismount(net.minecraft.world.entity.LivingEntity rider,Entity vehicle) {
        return ScarpetExplosionActors.entity(rider,()->vehicle!=rider.getVehicle()&&!rider.level().isClientSide()).thenCompose(detached->{
            if(!detached)return CompletableFuture.completedFuture(null);
            return ScarpetExplosionActors.entity(vehicle,()->new VehicleState((ServerLevel)vehicle.level(),vehicle.blockPosition().immutable(),vehicle.position(),vehicle.isRemoved(),vehicle.valid)).thenCompose(state->{
                if(!state.valid())return CompletableFuture.completedFuture(null);
                return ScarpetExplosionActors.entity(rider,()->rider.isRemoved()).thenCompose(removed->{
                    if(removed)return ScarpetExplosionActors.entity(rider,()->{
                        Vec3 own=rider.position();rider.dismountTo(own.x,own.y,own.z);return null;
                    });
                    return portalInRiderWorld(rider,state.block()).thenCompose(portal->{
                                if(state.removed()||portal)return fallbackDismount(rider,state.position());
                                return ScarpetExplosionActors.entity(rider,()->ScarpetDismountView.capture(rider)).thenCompose(view->
                                    vehicleDismount(vehicle,rider,view).thenCompose(result->applyDismount(rider,result)));
                            });
                });
            });
        });
    }
    private static CompletableFuture<Boolean> portalInRiderWorld(net.minecraft.world.entity.LivingEntity rider,BlockPos position) {
        return ScarpetExplosionActors.entity(rider,()->(ServerLevel)rider.level()).thenCompose(world->{
            var portal=fun.bm.lophine.carpet.CarpetRegionLease.<Boolean>runValue(world,position.getX()>>4,position.getZ()>>4,position.getX()>>4,position.getZ()>>4,
                lease->world.getBlockState(position).is(net.minecraft.tags.BlockTags.PORTALS));
            var validate=ScarpetRuntime.<Boolean,CompletableFuture<Boolean>>captureNativeFunction(value->ScarpetExplosionActors.entity(rider,()->
                rider.level()==world?CompletableFuture.completedFuture(value):portalInRiderWorld(rider,position)).thenCompose(result->result));
            return portal.thenCompose(validate);
        });
    }
    private static CompletableFuture<ScarpetDismountView.Result> vehicleDismount(Entity vehicle,net.minecraft.world.entity.LivingEntity rider,ScarpetDismountView.View view) {
        return ScarpetExplosionActors.entity(vehicle,()->{
            ServerLevel world=(ServerLevel)vehicle.level();BlockPos position=vehicle.blockPosition().immutable();
            double reach=vehicle.getBbWidth();for(var dimensions:view.dimensions.values())reach=Math.max(reach,dimensions.width());
            int radius=(int)Math.ceil(reach*2)+32,minX=(position.getX()-radius)>>4,maxX=(position.getX()+radius)>>4,minZ=(position.getZ()-radius)>>4,maxZ=(position.getZ()+radius)>>4;
            var perform=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(vehicle,()->{
                BlockPos current=vehicle.blockPosition();
                if(vehicle.level()!=world||((current.getX()-radius)>>4)<minX||((current.getX()+radius)>>4)>maxX||((current.getZ()-radius)>>4)<minZ||((current.getZ()+radius)>>4)>maxZ)
                    return vehicleDismount(vehicle,rider,view);
                return CompletableFuture.completedFuture(ScarpetDismountView.calculate(view,()->vehicle.getDismountLocationForPassenger(rider)));
            }).thenCompose(value->value));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<ScarpetDismountView.Result>>runValue(world,minX,minZ,maxX,maxZ,lease->perform.get()).thenCompose(value->value);
        }).thenCompose(value->value);
    }
    private static CompletableFuture<Void> fallbackDismount(net.minecraft.world.entity.LivingEntity rider,Vec3 vehiclePosition) {
        return riderArea(rider,vehiclePosition,()->{
            double y=Math.max(rider.getY(),vehiclePosition.y);Vec3 target=new Vec3(rider.getX(),y,rider.getZ());
            if(rider.getBbWidth()<=4.0F&&rider.getBbHeight()<=4.0F) {
                double half=rider.getBbHeight()/2D;Vec3 center=target.add(0,half,0);
                var allowed=net.minecraft.world.phys.shapes.Shapes.create(net.minecraft.world.phys.AABB.ofSize(center,rider.getBbWidth(),rider.getBbHeight(),rider.getBbWidth()));
                target=rider.level().findFreePosition(rider,allowed,center,rider.getBbWidth(),rider.getBbHeight(),rider.getBbWidth()).map(position->position.add(0,-half,0)).orElse(target);
            }
            rider.dismountTo(target.x,target.y,target.z);
        });
    }
    private static CompletableFuture<Void> applyDismount(net.minecraft.world.entity.LivingEntity rider,ScarpetDismountView.Result result) {
        return riderArea(rider,result.position(),()->{
            if(result.pose()!=null)rider.setPose(result.pose());Vec3 target=result.position();rider.dismountTo(target.x,target.y,target.z);
        });
    }
    private static CompletableFuture<Void> riderArea(net.minecraft.world.entity.LivingEntity rider,Vec3 destination,Runnable physical) {
        return ScarpetExplosionActors.entity(rider,()->{
            ServerLevel world=(ServerLevel)rider.level();BlockPos own=rider.blockPosition(),target=BlockPos.containing(destination);
            int margin=(int)Math.ceil(rider.getBbWidth())+16,minX=(Math.min(own.getX(),target.getX())-margin)>>4,maxX=(Math.max(own.getX(),target.getX())+margin)>>4,
                minZ=(Math.min(own.getZ(),target.getZ())-margin)>>4,maxZ=(Math.max(own.getZ(),target.getZ())+margin)>>4;
            var perform=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(rider,()->{
                BlockPos now=rider.blockPosition();
                if(rider.level()!=world||((now.getX()-margin)>>4)<minX||((now.getX()+margin)>>4)>maxX||((now.getZ()-margin)>>4)<minZ||((now.getZ()+margin)>>4)>maxZ)return riderArea(rider,destination,physical);
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(rider,()->{physical.run();return null;}));
            }).thenCompose(value->value));
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(world,minX,minZ,maxX,maxZ,lease->perform.get()).thenCompose(value->value);
        }).thenCompose(value->value);
    }
}
