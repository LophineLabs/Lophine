// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetContext;
import carpet.script.argument.Vector3Argument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.BlockValue;
import carpet.script.value.BooleanValue;
import carpet.script.value.NBTSerializableValue;
import carpet.script.value.Value;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs item/block placement on its complete vanilla feature footprint, then awaits typed Native outcomes.
 */
public final class ScarpetPlaceItem {
    private ScarpetPlaceItem() {
    }

    public static Value place(CarpetContext context, List<Value> args) {
        if (args.size() < 2)
            throw new InternalExpressionException("'place_item' takes at least 2 parameters: item and block, or position, to place onto");
        List<Value> captured = ActorFunctions.snapshotArguments(args);
        Vector3Argument locator = Vector3Argument.findIn(captured, 1);
        ItemStack stack = NBTSerializableValue.parseItem(captured.getFirst().getString(), context.registryAccess());
        BlockPos where = BlockPos.containing(locator.vec);
        String facing = captured.size() > locator.offset ? captured.get(locator.offset).getString() : stack.getItem() != Items.PAINTING ? "up" : "north";
        boolean sneak = captured.size() > locator.offset + 1 && captured.get(locator.offset + 1).getBoolean();
        CompletableFuture<Value> placed = ScarpetRuntime.withArea(context.level(), (where.getX() - 128) >> 4, (where.getZ() - 128) >> 4, (where.getX() + 128) >> 4, (where.getZ() + 128) >> 4, () -> {
            var placement = BlockValue.PlacementContext.from(context.level(), where, facing, sneak, stack);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                InteractionResult result = placement.getItemInHand().useOn(placement);
                if (result instanceof InteractionResult.Deferred deferred)
                    return deferred.plan().future().thenApply(ScarpetPlaceItem::accepted);
                return CompletableFuture.completedFuture(accepted(result));
            }
            if (placement.canPlace()) {
                var state = blockItem.getBlock().getStateForPlacement(placement);
                if (state != null && state.canSurvive(context.level(), where)) {
                    context.level().setBlock(where, state, 2);
                    var sound = state.getSoundType();
                    context.level().playSound(null, where, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
                    return CompletableFuture.completedFuture(Value.TRUE);
                }
            }
            return CompletableFuture.completedFuture(Value.FALSE);
        });
        return ScarpetRuntime.await(placed);
    }

    private static Value accepted(InteractionResult result) {
        return BooleanValue.of(result == InteractionResult.CONSUME || result == InteractionResult.SUCCESS);
    }
}
