// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;

/** Spawn-related rule tails follow the actual native creation, initialization and admission. */
public final class CarpetNativeSpawns {
    private CarpetNativeSpawns(){}
    public static CompletableFuture<Void> guardian(Guardian guardian,ServerLevel supplied,Supplier<ElderGuardian> create,Runnable rejected){
        ServerLevel admitted=(ServerLevel)guardian.level();
        return ScarpetLootActors.jobNative(admitted,()->TisCommandContinuations.then(ScarpetLootConditions.actor(guardian,create),elder->{
            var placed=ScarpetLootConditions.actor(guardian,()->{
                elder.snapTo(guardian.getX(),guardian.getY(),guardian.getZ(),guardian.getYRot(),guardian.getXRot());
                return elder.blockPosition().immutable();
            });
            var difficulty=TisCommandContinuations.then(placed,position->ScarpetLootActors.original(supplied,net.minecraft.world.phys.Vec3.atCenterOf(position),()->supplied.getCurrentDifficultyAt(position)));
            var initialized=TisCommandContinuations.then(difficulty,local->ScarpetLootActors.original(admitted,elder.position(),()->{
                elder.finalizeSpawn(supplied,local,EntitySpawnReason.CONVERSION,null);return (Void)null;
            }));
            var named=TisCommandContinuations.then(initialized,ignored->ScarpetLootConditions.actor(guardian,()->{
                elder.setNoAi(guardian.isNoAi());
                if(guardian.hasCustomName()){elder.setCustomName(guardian.getCustomName());elder.setCustomNameVisible(guardian.isCustomNameVisible());}
                return (ServerLevel)guardian.level();
            }));
            var added=TisCommandContinuations.then(named,original->ScarpetLootActors.original(original,elder.position(),()->original.addFreshEntity(elder,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING)));
            return TisCommandContinuations.then(added,accepted->ScarpetLootConditions.actor(guardian,()->{
                // Paper cancellation retains the original guardian and its normal lightning response.
                if(accepted)guardian.discard();else rejected.run();return (Void)null;
            }));
        }));
    }
    public static CompletableFuture<Void> infestation(ServerLevel original,BlockPos position,Supplier<Silverfish> create,Consumer<Silverfish> prepare,Runnable gravel){
        BlockPos origin=position.immutable();
        return ScarpetLootActors.jobNative(original,()->TisCommandContinuations.then(ScarpetLootActors.original(original,net.minecraft.world.phys.Vec3.atCenterOf(origin),create),fish->{
            if(fish==null)return CompletableFuture.completedFuture(null);
            var prepared=ScarpetLootActors.original(original,net.minecraft.world.phys.Vec3.atCenterOf(origin),()->{prepare.accept(fish);return (Void)null;});
            var added=TisCommandContinuations.then(prepared,ignored->ScarpetLootActors.original(original,net.minecraft.world.phys.Vec3.atCenterOf(origin),()->{
                original.addFreshEntity(fish,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK);return (Void)null;
            }));
            var animated=TisCommandContinuations.then(added,ignored->ScarpetLootConditions.actor(fish,()->{fish.spawnAnim();return (Void)null;}));
            return TisCommandContinuations.then(animated,ignored->ScarpetLootActors.original(original,net.minecraft.world.phys.Vec3.atCenterOf(origin),()->{
                if(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.silverFishDropGravel)gravel.run();return (Void)null;
            }));
        }));
    }
}
