// SPDX-License-Identifier: MIT
package carpet.script.external;

import fun.bm.lophine.carpet.CarpetRegionLease;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Loaded predicates preserve native load checks; structure ownership follows actual reference coordinates. */
public final class ScarpetLocationPredicates {
    private ScarpetLocationPredicates() {}
    public static CompletableFuture<Boolean> matches(LocationPredicate predicate,ServerLevel world,Vec3 position){
        var actual=new CompletableFuture<Boolean>();ScarpetNativeWork.record(actual);var caller=ScarpetNativeWork.trackNative(world.getServer(),actual);
        try{
            var job=begin(predicate,world,position);ScarpetNativeWork.aliasDependency(actual,job);
            job.whenComplete((matched,failure)->{if(failure==null)actual.complete(matched);else actual.completeExceptionally(failure);});
        }catch(Throwable failure){actual.completeExceptionally(failure);}
        return caller;
    }
    private static CompletableFuture<Boolean> begin(LocationPredicate predicate,ServerLevel world,Vec3 position){
        BlockPos pos=BlockPos.containing(position);
        if(!world.isLoaded(pos))return CompletableFuture.completedFuture(predicate.carpetMatchesUnloaded(world,position.x,position.y,position.z));
        return area(world,(pos.getX()-16)>>4,(pos.getZ()-16)>>4,(pos.getX()+16)>>4,(pos.getZ()+16)>>4,()->{
            if(predicate.structures().isEmpty())return CompletableFuture.completedFuture(predicate.matches(world,position.x,position.y,position.z));
            if(!predicate.carpetMatchesBeforeStructure(world,position.x,position.y,position.z)||!world.isLoaded(pos))return CompletableFuture.completedFuture(false);
            long[] references=references(predicate,world,pos);
            int minX=(pos.getX()-16)>>4,minZ=(pos.getZ()-16)>>4,maxX=(pos.getX()+16)>>4,maxZ=(pos.getZ()+16)>>4;
            for(long key:references){minX=Math.min(minX,ChunkPos.getX(key));maxX=Math.max(maxX,ChunkPos.getX(key));minZ=Math.min(minZ,ChunkPos.getZ(key));maxZ=Math.max(maxZ,ChunkPos.getZ(key));}
            final int fromX=minX,fromZ=minZ,toX=maxX,toZ=maxZ;
            return area(world,minX,minZ,maxX,maxZ,()->{
                if(!world.isLoaded(pos))return CompletableFuture.completedFuture(false);
                for(long key:references(predicate,world,pos)){
                    if(ChunkPos.getX(key)<fromX||ChunkPos.getX(key)>toX||ChunkPos.getZ(key)<fromZ||ChunkPos.getZ(key)>toZ)return begin(predicate,world,position);
                }
                // Only the original native algorithm loads its referenced STRUCTURE_STARTS chunks.
                // Ownership tickets never force FULL on the rectangle's previously unloaded chunks.
                return CompletableFuture.completedFuture(predicate.carpetMatchesAfterPrefix(world,position.x,position.y,position.z));
            });
        });
    }
    /** Retains the original structure access's own lower-status loads, including an initially unloaded origin. */
    public static <T> CompletableFuture<T> withStructureReferences(ServerLevel world,BlockPos pos,HolderSet<Structure> wanted,Supplier<T> body){
        var actual=new CompletableFuture<T>();ScarpetNativeWork.record(actual);var caller=ScarpetNativeWork.trackNative(world.getServer(),actual);
        try{
            var job=structureArea(world,pos.immutable(),wanted,body);ScarpetNativeWork.aliasDependency(actual,job);
            job.whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});
        }catch(Throwable failure){actual.completeExceptionally(failure);}
        return caller;
    }
    private static <T> CompletableFuture<T> structureArea(ServerLevel world,BlockPos pos,HolderSet<Structure> wanted,Supplier<T> body){
        return area(world,(pos.getX()-16)>>4,(pos.getZ()-16)>>4,(pos.getX()+16)>>4,(pos.getZ()+16)>>4,()->{
            long[] references=references(world,pos,wanted);
            int minX=(pos.getX()-16)>>4,minZ=(pos.getZ()-16)>>4,maxX=(pos.getX()+16)>>4,maxZ=(pos.getZ()+16)>>4;
            for(long key:references){minX=Math.min(minX,ChunkPos.getX(key));maxX=Math.max(maxX,ChunkPos.getX(key));minZ=Math.min(minZ,ChunkPos.getZ(key));maxZ=Math.max(maxZ,ChunkPos.getZ(key));}
            final int fromX=minX,fromZ=minZ,toX=maxX,toZ=maxZ;
            return area(world,minX,minZ,maxX,maxZ,()->{
                for(long key:references(world,pos,wanted))if(ChunkPos.getX(key)<fromX||ChunkPos.getX(key)>toX||ChunkPos.getZ(key)<fromZ||ChunkPos.getZ(key)>toZ)return structureArea(world,pos,wanted,body);
                return CompletableFuture.completedFuture(body.get());
            });
        });
    }
    private static long[] references(LocationPredicate predicate,ServerLevel world,BlockPos pos){return references(world,pos,predicate.structures().orElseThrow());}
    private static long[] references(ServerLevel world,BlockPos pos,HolderSet<Structure> wanted){
        var structures=world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var found=new it.unimi.dsi.fastutil.longs.LongArrayList();
        var chunk=world.getChunk(pos.getX()>>4,pos.getZ()>>4,ChunkStatus.STRUCTURE_REFERENCES);
        for(var entry:chunk.getAllReferences().entrySet()){
            var structure=entry.getKey();
            if(structures.get(structures.getId(structure)).map(wanted::contains).orElse(false))found.addAll(entry.getValue());
        }
        return found.toLongArray();
    }
    private static <T> CompletableFuture<T> area(ServerLevel world,int minX,int minZ,int maxX,int maxZ,Supplier<CompletableFuture<T>> body){
        var phase=ScarpetRuntime.captureNativeContinuation(()->{
            var observed=ScarpetNativeWork.observeNative(null,()->{var nativeBody=body.get();ScarpetNativeWork.record(nativeBody);return nativeBody;});
            return ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value->value);
        });
        var actual=CarpetRegionLease.<CompletableFuture<T>>runLoadedValue(world,minX,minZ,maxX,maxZ,lease->phase.get()).thenCompose(value->value);
        ScarpetNativeWork.record(actual);return actual;
    }
}
