// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

import java.util.concurrent.CompletableFuture;

public final class ScarpetLootFunctions {
    private ScarpetLootFunctions() {
    }

    public static CompletableFuture<ItemStack> apply(LootItemFunction function, ItemStack stack, LootContext context) {
        if (function instanceof LootItemConditionalFunction conditional) {
            var body = ScarpetRuntime.captureNativeContinuation(() -> conditional.carpetRunAsync(stack, context));
            return ScarpetLootTables.condition(conditional.carpetCondition(), context)
                    .thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> allowed ? body.get() : CompletableFuture.completedFuture(stack)));
        }
        return ScarpetLootActors.original(context, () -> function.apply(stack, context));
    }
}
