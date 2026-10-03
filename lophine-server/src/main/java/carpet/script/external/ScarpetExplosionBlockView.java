// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Calls the original source's virtual calculator on its real owner, with old-world block data from the old-world owner. */
public final class ScarpetExplosionBlockView {
    private static final class Missing extends RuntimeException {
        final BlockPos position;
        Missing(BlockPos position) { super(null,null,false,false); this.position=position.immutable(); }
    }
    private static final class View implements BlockGetter {
        final Map<BlockPos,BlockState> states=new HashMap<>();
        final int height,minY;
        View(int height,int minY){this.height=height;this.minY=minY;}
        @Override public BlockState getBlockState(BlockPos position){BlockState state=states.get(position);if(state==null)throw new Missing(position);return state;}
        @Override public BlockState getBlockStateIfLoaded(BlockPos position){return getBlockState(position);}
        @Override public FluidState getFluidState(BlockPos position){return getBlockState(position).getFluidState();}
        @Override public FluidState getFluidIfLoaded(BlockPos position){return getFluidState(position);}
        @Override public BlockEntity getBlockEntity(BlockPos position){throw new IllegalStateException("Pinned explosion source calculators do not read mutable block entities");}
        @Override public int getHeight(){return height;}
        @Override public int getMinY(){return minY;}
    }
    private record Result<T>(T value,BlockPos missing) {}
    private ScarpetExplosionBlockView(){}
    public static <T> CompletableFuture<T> calculate(ServerExplosion explosion,Entity source,BlockPos position,Function<BlockGetter,T> operation){
        CompletableFuture<T> done=ScarpetExplosionActors.world(explosion.level(),BlockPos.containing(explosion.center()),
            ()->new View(explosion.level().getHeight(),explosion.level().getMinY())).thenCompose(view->
                fill(explosion,view,position).thenCompose(ignored->evaluate(explosion,source,view,operation)));
        ScarpetNativeWork.record(done);return done;
    }
    private static CompletableFuture<Void> fill(ServerExplosion explosion,View view,BlockPos position){
        return ScarpetExplosionActors.blocks(explosion,List.of(position),()->{
            BlockState state=explosion.carpetBlockStateForCalculator(position);
            view.states.put(position.immutable(),state==null?Blocks.VOID_AIR.defaultBlockState():state);return null;
        });
    }
    private static <T> CompletableFuture<T> evaluate(ServerExplosion explosion,Entity source,View view,Function<BlockGetter,T> operation){
        return ScarpetExplosionActors.entity(source,()->{
            try{return new Result<T>(operation.apply(view),null);}catch(Missing missing){return new Result<T>(null,missing.position);}
        }).thenCompose(result->result.missing()==null?CompletableFuture.completedFuture(result.value()):
            fill(explosion,view,result.missing()).thenCompose(ignored->evaluate(explosion,source,view,operation)));
    }
}
