// SPDX-License-Identifier: MIT
package carpet.script.external;

import com.mojang.serialization.MapCodec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;

/** Each original passenger's actual save body executes on its owner; the parent writes those exact immutable child tags. */
public final class ScarpetPredicateNbt {
    private record Saved(boolean eligible,CompoundTag tag) {}
    private static final ThreadLocal<Map<Entity,Saved>> CURRENT=new ThreadLocal<>();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("Scarpet predicate NBT");
    private static final MapCodec<CompoundTag> MAP_CODEC=MapCodec.assumeMapUnsafe(CompoundTag.CODEC);
    private ScarpetPredicateNbt() {}
    public static CompletableFuture<CompoundTag> tag(Entity entity) {
        return children(entity).thenCompose(ScarpetRuntime.captureNativeFunction(children->ScarpetExplosionActors.entity(entity,()->{
            if(!same(entity.getPassengers(),children.keySet().stream().toList()))return tag(entity);
            return CompletableFuture.completedFuture(with(children,()->NbtPredicate.getEntityTagToCompare(entity)));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value))));
    }
    /** Null leaves the original native saveAsPassenger body active. Scoped snapshots never inspect a foreign mutable child. */
    public static Boolean writePassenger(Entity entity,ValueOutput output,boolean includeAll,boolean includeNonSaveable,boolean force) {
        Map<Entity,Saved> saved=CURRENT.get();if(saved==null||!includeAll||includeNonSaveable||force)return null;
        Saved value=saved.get(entity);if(value==null)return null;
        if(value.eligible())output.store(MAP_CODEC,value.tag().copy());return value.eligible();
    }
    private static <T>T with(Map<Entity,Saved> saved,Supplier<T> operation) {
        Map<Entity,Saved> previous=CURRENT.get();CURRENT.set(saved);
        try{return operation.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    private static CompletableFuture<Map<Entity,Saved>> children(Entity parent) {
        return ScarpetExplosionActors.entity(parent,()->List.copyOf(parent.getPassengers())).thenCompose(ScarpetRuntime.captureNativeFunction(passengers->{
            CompletableFuture<Map<Entity,Saved>> result=CompletableFuture.completedFuture(new IdentityHashMap<>());
            for(Entity passenger:passengers) {
                Supplier<CompletableFuture<Saved>> next=ScarpetRuntime.captureNativeContinuation(()->passenger(passenger));
                result=result.thenCompose(ScarpetRuntime.captureNativeFunction(values->next.get().thenApply(saved->{values.put(passenger,saved);return values;})));
            }
            return result;
        }));
    }
    private static CompletableFuture<Saved> passenger(Entity entity) {
        return children(entity).thenCompose(ScarpetRuntime.captureNativeFunction(children->ScarpetExplosionActors.entity(entity,()->{
            if(!containsSame(entity.getPassengers(),children))return passenger(entity);
            return CompletableFuture.completedFuture(with(children,()->{
                try(ProblemReporter.ScopedCollector reporter=new ProblemReporter.ScopedCollector(entity.problemPath(),LOGGER)) {
                    TagValueOutput output=TagValueOutput.createWithContext(reporter,entity.registryAccess());
                    boolean eligible=entity.saveAsPassenger(output,true,false,false);return new Saved(eligible,output.buildResult());
                }
            }));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value->value))));
    }
    private static boolean containsSame(List<Entity> current,Map<Entity,Saved> saved) {
        if(current.size()!=saved.size())return false;for(Entity entity:current)if(!saved.containsKey(entity))return false;return true;
    }
    private static boolean same(List<Entity> current,List<Entity> expected) {
        if(current.size()!=expected.size())return false;
        // The snapshot map is identity based; actual native save retains the current original list's ordering.
        for(Entity entity:current){boolean found=false;for(Entity old:expected)if(old==entity){found=true;break;}if(!found)return false;}return true;
    }
}
