// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;

/** A loaded-only native area owns both the player and items before normal pickup is invoked. */
public final class OrgExpandedItemPickup {
    private static final Map<ServerPlayer,Boolean> PENDING=Collections.synchronizedMap(new WeakHashMap<>());
    private OrgExpandedItemPickup() {}
    public static void expand(ServerPlayer player,AABB pickupArea){
        TickThread.ensureTickThread(player,"Expanded pickup must be requested by its player owner");
        int range=OrgRulePlayerPreferences.itemPickupRange(player);if(range<=0||player.isRemoved()||player.isDeadOrDying()||ScarpetPlayerInventoryGate.paused(player))return;
        ServerLevel world=player.level();AABB expanded=pickupArea.inflate(range);
        int minX=Mth.floor(expanded.minX)>>4,minZ=Mth.floor(expanded.minZ)>>4,maxX=Mth.floor(expanded.maxX)>>4,maxZ=Mth.floor(expanded.maxZ)>>4;
        if(TickThread.isTickThreadFor(world,minX,minZ,maxX,maxZ)){
            ScarpetPlayerInventoryGate.observeAccepted(player,()->{touch(player,world,range);return null;});return;
        }
        if(PENDING.putIfAbsent(player,true)!=null)return;
        var actual=ScarpetNativeWork.<CompletableFuture<Void>>observeNative(player,()->{
            var inherited=ScarpetRuntime.<CompletableFuture<Void>>captureNativeContinuation(()->{
                if(!TickThread.isTickThreadFor(player))return CompletableFuture.<Void>completedFuture(null);
                return ScarpetNativeWork.<Void>observeNative(player,()->{try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){touch(player,world,range);}return null;});
            });
            var area=CarpetRegionLease.<CompletableFuture<Void>>runLoadedValue(world,minX,minZ,maxX,maxZ,lease->{
                if(!lease.ownsAll())throw new IllegalStateException("Expanded pickup lost complete native ownership");return inherited.get();
            }).thenCompose(Function.identity());
            ScarpetNativeWork.record(area);
            var committed=area.handle((ignored,error)->error).thenCompose(error->OrgFakePlayerActions.owned(player,()->{
                try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){
                    PENDING.remove(player);if(error!=null)throw new java.util.concurrent.CompletionException(error);return (Void)null;
                }
            }));
            ScarpetNativeWork.record(committed);return committed;
        }).thenCompose(Function.identity());
        ScarpetPlayerInventoryGate.trackAccepted(player,actual);
        actual.whenComplete((ignored,error)->{if(error!=null){PENDING.remove(player);net.minecraft.server.MinecraftServer.LOGGER.error("Expanded item pickup area failed",error);}});
    }
    private static void touch(ServerPlayer player,ServerLevel world,int range){
        TickThread.ensureTickThread(player,"Expanded pickup requires both native owners");
        if(player.level()!=world||player.isRemoved()||player.isDeadOrDying()||player.isCreativeFlyOrSpectator()||OrgRulePlayerPreferences.itemPickupRange(player)!=range)return;
        // Use the actual current native pickup area after movement, mounting or teleport.
        AABB pickup;
        Entity vehicle=player.getVehicle();
        if(player.isPassenger()&&vehicle!=null){if(!TickThread.isTickThreadFor(vehicle))return;pickup=!vehicle.isRemoved()?player.getBoundingBox().minmax(vehicle.getBoundingBox()).inflate(1,0,1):player.getBoundingBox().inflate(1,0.5,1);}
        else pickup=player.getBoundingBox().inflate(1,0.5,1);
        AABB expanded=pickup.inflate(range);
        if(!TickThread.isTickThreadFor(world,Mth.floor(expanded.minX)>>4,Mth.floor(expanded.minZ)>>4,Mth.floor(expanded.maxX)>>4,Mth.floor(expanded.maxZ)>>4))return;
        for(Entity entity:world.getEntities(player,expanded,candidate->candidate.is(EntityTypes.ITEM))){
            if(entity.isRemoved())continue;TickThread.ensureTickThread(entity,"Expanded item pickup cannot cross a foreign owner");
            // Preserve the native Player.touch entry so Carpet's collision event fires too.
            player.touch(entity);
        }
    }
}
