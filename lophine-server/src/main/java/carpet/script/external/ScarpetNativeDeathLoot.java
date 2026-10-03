// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.List;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;

/** Native carried-block drops have no BLOCK_ENTITY, preserving all five native overrides for that exact context. */
public final class ScarpetNativeDeathLoot {
    private ScarpetNativeDeathLoot() {}
    /** Live source iterators are read on the owner; each step awaits its real native children. */
    public static <T> CompletableFuture<Void> sequence(LivingEntity target,Iterator<T> original,Consumer<T> effect,Runnable finish){
        var actual=ScarpetNativeDeathActors.target(target,()->{
            if(!original.hasNext()){finish.run();return false;}
            effect.accept(original.next());return true;
        }).thenCompose(ScarpetRuntime.captureNativeFunction(more->more?sequence(target,original,effect,finish):CompletableFuture.completedFuture(null)));
        ScarpetNativeWork.record(actual);return actual;
    }
    public static CompletableFuture<List<ItemStack>> carriedBlockDrops(BlockState state,LootParams.Builder params){
        if(state.getBlock() instanceof LiquidBlock)return CompletableFuture.completedFuture(List.of());
        if(state.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock){
            return ScarpetLootActors.original(params.getLevel(),params.getParameter(LootContextParams.ORIGIN),()->{
                var blockEntity=params.getLevel().getBlockEntity(BlockPos.containing(params.getParameter(LootContextParams.ORIGIN)));
                return blockEntity instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston?piston.getMovedState():null;
            }).thenCompose(ScarpetRuntime.captureNativeFunction(moved->moved==null?CompletableFuture.completedFuture(List.of()):carriedBlockDrops(moved,params)));
        }
        // Beehive reads THIS_ENTITY (the real Enderman), so its explosive-release branch is false.
        // Shulker and decorated-pot prefixes do nothing without BLOCK_ENTITY, as the source Enderman supplies.
        var key=state.getBlock().getLootTable();if(key.isEmpty())return CompletableFuture.completedFuture(List.of());
        var actualParams=params.withParameter(LootContextParams.BLOCK_STATE,state).create(LootContextParamSets.BLOCK);
        var table=actualParams.getLevel().getServer().reloadableRegistries().getLootTable(key.get());
        return table.carpetGetRandomItemsNativeAsync(actualParams).thenApply(items->items);
    }
}
