// SPDX-License-Identifier: MIT
package carpet.script.external;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.*;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The original loot walker, retaining ordered conditions, rolls, weighted entries, nested tables and modifiers.
 */
public final class ScarpetLootTables {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private ScarpetLootTables() {
    }

    @FunctionalInterface
    public interface Output {
        CompletableFuture<Void> accept(ItemStack stack);
    }

    public record Entry(LootPoolEntry nativeEntry,
                        java.util.function.Function<Output, CompletableFuture<Void>> produce) {
    }

    public static CompletableFuture<Void> items(LootTable table, LootParams params, long seed, Consumer<ItemStack> output) {
        var actual = itemsNative(table, params, seed, output);
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    public static CompletableFuture<Void> itemsNative(LootTable table, LootParams params, long seed, Consumer<ItemStack> output) {
        return ScarpetLootActors.jobNative(params.getLevel(), () -> context(table, params, seed).thenCompose(ScarpetRuntime.captureNativeFunction(context -> {
            var split = LootTable.createStackSplitter(params.getLevel(), output);
            return raw(table, context, stack -> ScarpetLootActors.recipient(context, () -> {
                split.accept(stack);
                return null;
            }));
        })));
    }

    public static CompletableFuture<ObjectArrayList<ItemStack>> items(LootTable table, LootParams params) {
        var actual = itemsNative(table, params);
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    public static CompletableFuture<ObjectArrayList<ItemStack>> itemsNative(LootTable table, LootParams params) {
        ObjectArrayList<ItemStack> result = new ObjectArrayList<>();
        return ScarpetLootActors.jobNative(params.getLevel(), () -> itemsNative(table, params, 0L, result::add).thenApply(ignored -> result));
    }

    private static CompletableFuture<LootContext> context(LootTable table, LootParams params, long seed) {
        Vec3 origin = params.contextMap().get(LootContextParams.ORIGIN);
        return ScarpetLootActors.original(params.getLevel(), origin == null ? Vec3.ZERO : origin,
                () -> new LootContext.Builder(params).withOptionalRandomSeed(seed).create(table.carpetRandomSequence()));
    }

    public static CompletableFuture<Void> raw(LootTable table, LootContext context, Output output) {
        var breadcrumb = LootContext.createVisitedEntry(table);
        return ScarpetLootActors.original(context, () -> context.pushVisitedElement(breadcrumb)).thenCompose(ScarpetRuntime.captureNativeFunction(entered -> {
            if (!entered) {
                return ScarpetLootActors.original(context, () -> {
                    LOGGER.warn("Detected infinite loop in loot tables");
                    return null;
                });
            }
            Output decorated = decorate(table.carpetModifier(), context, output);
            return pools(table.pools, context, decorated, 0).thenCompose(ScarpetRuntime.captureNativeFunction(ignored ->
                    ScarpetLootActors.original(context, () -> {
                        context.popVisitedElement(breadcrumb);
                        return null;
                    })));
        }));
    }

    private static CompletableFuture<Void> pools(List<LootPool> pools, LootContext context, Output output, int index) {
        if (index == pools.size()) return CompletableFuture.completedFuture(null);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> pools(pools, context, output, index + 1));
        LootPool pool = pools.get(index);
        return condition(pool.carpetCondition(), context).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> {
            if (!allowed) return next.get();
            Output decorated = decorate(pool.carpetModifier(), context, output);
            return ScarpetLootNumbers.integer(pool.carpetRolls(), context).thenCompose(ScarpetRuntime.captureNativeFunction(rolls ->
                            ScarpetLootNumbers.floating(pool.carpetBonusRolls(), context).thenCompose(ScarpetRuntime.captureNativeFunction(bonus ->
                                    rolls(pool.entries, context, decorated, rolls + Mth.floor(bonus * context.getLuck()), 0)))))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
        }));
    }

    private static CompletableFuture<Void> rolls(List<LootPoolEntryContainer> entries, LootContext context, Output output, int count, int index) {
        if (index >= count) return CompletableFuture.completedFuture(null);
        List<Entry> valid = new ArrayList<>();
        return expandAll(entries, context, valid::add, 0).thenCompose(ScarpetRuntime.captureNativeFunction(ignored ->
                ScarpetLootActors.original(context, () -> {
                    int total = 0;
                    List<Entry> positive = new ArrayList<>();
                    for (Entry entry : valid) {
                        int weight = entry.nativeEntry().getWeight(context.getLuck());
                        if (weight > 0) {
                            total += weight;
                            positive.add(entry);
                        }
                    }
                    if (total == 0 || positive.isEmpty()) return null;
                    if (positive.size() == 1) return positive.getFirst();
                    int choice = context.getRandom().nextInt(total);
                    for (Entry entry : positive) {
                        choice -= entry.nativeEntry().getWeight(context.getLuck());
                        if (choice < 0) return entry;
                    }
                    return null;
                }).thenCompose(ScarpetRuntime.captureNativeFunction(selected -> {
                    Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> rolls(entries, context, output, count, index + 1));
                    return selected == null ? next.get() : selected.produce().apply(output).thenCompose(ScarpetRuntime.captureNativeFunction(done -> next.get()));
                }))));
    }

    public static CompletableFuture<Void> expandAll(List<LootPoolEntryContainer> entries, LootContext context, Consumer<Entry> output, int index) {
        if (index == entries.size()) return CompletableFuture.completedFuture(null);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> expandAll(entries, context, output, index + 1));
        return expand(entries.get(index), context, output).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }

    public static CompletableFuture<Boolean> expand(LootPoolEntryContainer container, LootContext context, Consumer<Entry> output) {
        Consumer<Entry> decorated = entry -> output.accept(new Entry(entry.nativeEntry(), downstream ->
                entry.produce().apply(decorate(container.carpetModifier(), context, downstream))));
        return condition(container.condition, context).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> {
            if (!allowed) return CompletableFuture.completedFuture(false);
            if (container instanceof CompositeEntryBase composite) return children(composite, context, decorated, 0);
            return ScarpetLootActors.original(context, () -> {
                List<LootPoolEntry> entries = new ArrayList<>();
                boolean expanded = container.carpetExpandRaw(context, entries::add);
                if (container instanceof NestedLootTable nested) {
                    List<Holder<LootTable>> tables = nested.value.stream().toList();
                    for (int i = 0; i < entries.size(); ++i) {
                        LootPoolEntry nativeEntry = entries.get(i);
                        List<Holder<LootTable>> selected = nested.carpetExpanded() ? List.of(tables.get(i)) : tables;
                        decorated.accept(new Entry(nativeEntry, downstream -> nested(selected, context, downstream, 0)));
                    }
                } else for (LootPoolEntry entry : entries)
                    decorated.accept(new Entry(entry, downstream -> container instanceof SlotLoot slot
                            ? slot.carpetCopiesAsync(context).thenCompose(ScarpetRuntime.captureNativeFunction(items -> emit(items, downstream, 0)))
                            : produce(entry, context, downstream)));
                return expanded;
            });
        }));
    }

    private static CompletableFuture<Boolean> children(CompositeEntryBase composite, LootContext context, Consumer<Entry> output, int index) {
        if (index == composite.children.size())
            return CompletableFuture.completedFuture(!(composite instanceof AlternativesEntry));
        Supplier<CompletableFuture<Boolean>> next = ScarpetRuntime.captureNativeContinuation(() -> children(composite, context, output, index + 1));
        return expand(composite.children.get(index), context, output).thenCompose(ScarpetRuntime.captureNativeFunction(expanded -> {
            if (composite instanceof AlternativesEntry && expanded) return CompletableFuture.completedFuture(true);
            if (composite instanceof SequentialEntry && !expanded) return CompletableFuture.completedFuture(false);
            if (composite instanceof EntryGroup && composite.children.size() == 1)
                return CompletableFuture.completedFuture(expanded);
            return next.get();
        }));
    }

    private static CompletableFuture<Void> nested(List<Holder<LootTable>> tables, LootContext context, Output output, int index) {
        if (index == tables.size()) return CompletableFuture.completedFuture(null);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> nested(tables, context, output, index + 1));
        return raw(tables.get(index).value(), context, output).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }

    private static CompletableFuture<Void> produce(LootPoolEntry entry, LootContext context, Output output) {
        return ScarpetLootActors.recipient(context, () -> {
                    List<ItemStack> items = new ArrayList<>();
                    entry.createItemStack(items::add, context);
                    return items;
                })
                .thenCompose(ScarpetRuntime.captureNativeFunction(items -> emit(items, output, 0)));
    }

    public static CompletableFuture<Void> emit(List<ItemStack> items, Output output, int index) {
        if (index == items.size()) return CompletableFuture.completedFuture(null);
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> emit(items, output, index + 1));
        return output.accept(items.get(index)).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> next.get()));
    }

    public static CompletableFuture<Void> contents(List<LootPoolEntryContainer> containers, LootContext context, Output output, int index) {
        if (index == containers.size()) return CompletableFuture.completedFuture(null);
        List<Entry> expanded = new ArrayList<>();
        Supplier<CompletableFuture<Void>> next = ScarpetRuntime.captureNativeContinuation(() -> contents(containers, context, output, index + 1));
        return expand(containers.get(index), context, expanded::add).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> {
            CompletableFuture<Void> produced = CompletableFuture.completedFuture(null);
            for (Entry entry : expanded)
                produced = produced.thenCompose(ScarpetRuntime.captureNativeFunction(done -> entry.produce().apply(output)));
            return produced.thenCompose(ScarpetRuntime.captureNativeFunction(done -> next.get()));
        }));
    }

    public static CompletableFuture<Boolean> condition(Optional<Holder<LootItemCondition>> condition, LootContext context) {
        return condition.isEmpty() ? CompletableFuture.completedFuture(true) : ScarpetLootConditions.test(condition.get().value(), context);
    }

    public static Output decorate(Optional<Holder<LootItemFunction>> modifier, LootContext context, Output output) {
        return modifier.isEmpty() ? output : stack -> ScarpetLootFunctions.apply(modifier.get().value(), stack, context)
                .thenCompose(ScarpetRuntime.captureNativeFunction(output::accept));
    }
}
