// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Actual attack data crosses actors only as narrow immutable values at the native phase which reads it. */
public final class ScarpetNativeAttackBodies {
    public record MaceVictim(Vec3 position,AABB box,BlockPos onPos,boolean onGround,UUID uuid) {}
    public record DamageRead(float health,double x,double middleY,double z) {}
    private record MaceScope(Entity actual,MaceVictim value) {}
    private static final ThreadLocal<MaceScope> MACE=new ThreadLocal<>();
    private ScarpetNativeAttackBodies() {}
    public static CompletableFuture<MaceVictim> maceVictim(LivingEntity target){
        return ScarpetNativeDeathActors.target(target,()->new MaceVictim(target.position(),target.getBoundingBox(),target.getOnPos().immutable(),target.onGround(),target.getUUID()));
    }
    public static MaceVictim currentMace(Entity target){var scope=MACE.get();return scope!=null&&scope.actual()==target?scope.value():null;}
    public static void withMace(Entity target,MaceVictim value,Runnable operation){
        var previous=MACE.get();MACE.set(new MaceScope(target,value));try{operation.run();}finally{if(previous==null)MACE.remove();else MACE.set(previous);}
    }
    public static CompletableFuture<DamageRead> damage(Entity target){
        if(!(target instanceof LivingEntity living))return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(living,()->new DamageRead(living.getHealth(),living.getX(),living.getY(0.5),living.getZ()));
    }
    /** The native cached world is independent of the source actor's subsequent world. */
    public static <T> CompletableFuture<T> worldArea(ServerLevel world,AABB area,Entity observer,Supplier<T> operation){
        Supplier<CompletableFuture<T>> captured=ScarpetRuntime.captureNativeContinuation(()->
            ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(observer,operation)));
        var result=fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<T>>runLoadedValue(world,
            (int)Math.floor((area.minX-1D)/16D),(int)Math.floor((area.minZ-1D)/16D),
            (int)Math.floor((area.maxX+1D)/16D),(int)Math.floor((area.maxZ+1D)/16D),lease->captured.get())
            .thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        ScarpetNativeWork.record(result);return result;
    }
    public static <T> CompletableFuture<T> area(LivingEntity actor,AABB extra,Supplier<T> operation){return area(actor,extra,operation,0);}
    private static <T> CompletableFuture<T> area(LivingEntity actor,AABB extra,Supplier<T> operation,int attempt){
        if(attempt==8)return CompletableFuture.failedFuture(new IllegalStateException("Native attack source kept changing regions"));
        return ScarpetExplosionActors.entity(actor,()->{
            ServerLevel world=(ServerLevel)actor.level();BlockPos position=actor.blockPosition().immutable();
            int minX=(int)Math.floor((Math.min(position.getX()-32D,extra.minX)-1D)/16D);
            int minZ=(int)Math.floor((Math.min(position.getZ()-32D,extra.minZ)-1D)/16D);
            int maxX=(int)Math.floor((Math.max(position.getX()+32D,extra.maxX)+1D)/16D);
            int maxZ=(int)Math.floor((Math.max(position.getZ()+32D,extra.maxZ)+1D)/16D);
            var perform=ScarpetRuntime.captureNativeContinuation(()->ScarpetExplosionActors.entity(actor,()->{
                BlockPos now=actor.blockPosition();
                if(actor.level()!=world||((now.getX()-32)>>4)<minX||((now.getX()+32)>>4)>maxX||((now.getZ()-32)>>4)<minZ||((now.getZ()+32)>>4)>maxZ)
                    return area(actor,extra,operation,attempt+1);
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(actor,operation));
            }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value)));
            // Native attack queries existing entities; a foreign projection must not load empty chunks.
            return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<T>>runLoadedValue(world,minX,minZ,maxX,maxZ,lease->perform.get())
                .thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
    }
}
