// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.slot.*;
import net.minecraft.world.level.storage.loot.LootContext;

/** Loot only receives actual owner-produced item copies; live slot handles never leave their actor. */
public final class ScarpetLootSlots {
    private ScarpetLootSlots() { }
    public static CompletableFuture<List<ItemStack>> copies(SlotSource source, LootContext context) {
        if (source instanceof RangeSlotSource range) {
            Object actual = range.carpetActualSource(context);
            return ScarpetLootActors.reference(context, actual, () -> range.provide(context).itemCopies().toList());
        }
        if (source instanceof CompositeSlotSource group) {
            CompletableFuture<List<ItemStack>> result = CompletableFuture.completedFuture(new ArrayList<>());
            for (var term : group.carpetTerms()) result = result.thenCompose(ScarpetRuntime.captureNativeFunction(values ->
                copies(term.value(), context).thenApply(items -> { values.addAll(items); return values; })));
            return result;
        }
        if (source instanceof TransformedSlotSource transformed) return copies(transformed.carpetSource(), context)
            .thenCompose(ScarpetRuntime.captureNativeFunction(items -> ScarpetLootActors.original(context, () -> transformed.carpetTransformCopies(items))));
        return ScarpetLootActors.original(context, () -> source.provide(context).itemCopies().toList());
    }
    /** Each item was already copied by its native slot owner; transformations keep the original copy count. */
    public static SlotCollection snapshot(List<ItemStack> copies) {
        return new SlotCollection() {
            @Override public Stream<ItemStack> itemCopies() { return copies.stream(); }
            @Override public int size() { return copies.size(); }
            @Override public int replaceSlotItems(ItemProvider items, SlotSelector selector) { throw new UnsupportedOperationException("Loot slot snapshots are read-only"); }
            @Override public void modifySlots(Consumer<? super SlotAccess> consumer, SlotSelector selector) { throw new UnsupportedOperationException("Loot slot snapshots are read-only"); }
        };
    }
}
