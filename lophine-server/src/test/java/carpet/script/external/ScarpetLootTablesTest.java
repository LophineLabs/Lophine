package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.entries.*;
import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetContainerContents;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetLootTablesTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Production data-pack loading binds item components; this controlled Native fixture supplies its required stack limit.
        for (var item : List.of(Items.STONE, Items.DIRT, Items.CHEST)) {
            try {
                item.builtInRegistryHolder().components();
            } catch (NullPointerException unbound) {
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                        .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
            }
        }
    }

    final MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
    final ServerLevel world = mock(ServerLevel.class);
    final GlobalConfiguration global = mock(GlobalConfiguration.class);
    final WorldConfiguration config = mock(WorldConfiguration.class);

    @BeforeEach
    void setup() {
        when(world.getServer()).thenReturn(server);
        when(world.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        global.misc = global.new Misc();
        config.fixes = config.new Fixes();
        when(world.paperConfig()).thenReturn(config);
    }

    private final class Owners implements AutoCloseable {
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<GlobalConfiguration> configs = mockStatic(GlobalConfiguration.class);

        Owners() {
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            configs.when(GlobalConfiguration::get).thenReturn(global);
        }

        @Override
        public void close() {
            configs.close();
            ticks.close();
        }
    }

    private LootParams params() {
        return new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN, new Vec3(3, 65, 8)).create(LootContextParamSets.CHEST);
    }

    private LootContext context(long seed) {
        return new LootContext.Builder(params()).withOptionalRandomSeed(seed).create(Optional.empty());
    }

    private List<String> describe(List<ItemStack> items) {
        return items.stream().map(stack -> stack.getItem().toString() + ":" + stack.getCount()).toList();
    }

    @Test
    void seededRealNativeTableMatchesWholeOrderedWalkerForThirtyTwoRandomStreams() {
        var table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ContextIntProviders.between(2, 6))
                .add(LootItem.lootTableItem(Items.STONE).setWeight(2).apply(SetItemCountFunction.setCount(ContextIntProviders.between(1, 5))))
                .add(LootItem.lootTableItem(Items.DIRT).setWeight(3).apply(SetItemCountFunction.setCount(ContextIntProviders.between(4, 8))))).build();
        try (var owners = new Owners()) {
            for (long seed = 1; seed <= 32; ++seed) {
                List<ItemStack> expected = new ArrayList<>();
                table.getRandomItems(params(), seed, expected::add);
                List<ItemStack> actual = new ArrayList<>();
                table.carpetGetRandomItemsAsync(params(), seed, actual::add).join();
                assertEquals(describe(expected), describe(actual), "whole source random oracle seed " + seed);
            }
        }
    }

    @Test
    void nativeNestedTableAndEveryModifierRetainSourceNestingAndRandomOrder() {
        var inner = LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.STONE)
                        .apply(SetItemCountFunction.setCount(ContextIntProviders.between(2, 9)))).apply(SetItemCountFunction.setCount(ContextIntProviders.between(1, 3), true)))
                .apply(SetItemCountFunction.setCount(ContextIntProviders.between(2, 5), true)).build();
        var outer = LootTable.lootTable().withPool(LootPool.lootPool().add(NestedLootTable.inlineLootTable(inner)
                        .apply(SetItemCountFunction.setCount(ContextIntProviders.between(1, 4), true))))
                .apply(SetItemCountFunction.setCount(ContextIntProviders.between(2, 7), true)).build();
        try (var owners = new Owners()) {
            List<ItemStack> expected = new ArrayList<>();
            outer.getRandomItems(params(), 91, expected::add);
            List<ItemStack> actual = new ArrayList<>();
            outer.carpetGetRandomItemsAsync(params(), 91, actual::add).join();
            assertEquals(describe(expected), describe(actual));
        }
    }

    @Test
    void sourceEntryGroupSingleFalsePreservesAlternativesFallthrough() {
        var reject = mock(LootItemCondition.class);
        var context = context(32);
        when(reject.test(context)).thenReturn(false);
        var single = EntryGroup.list(LootItem.lootTableItem(Items.STONE).when(Holder.direct(reject)));
        var alternatives = AlternativesEntry.alternatives(single, LootItem.lootTableItem(Items.DIRT)).build();
        try (var owners = new Owners()) {
            List<LootPoolEntry> expected = new ArrayList<>();
            assertTrue(alternatives.expand(context, expected::add));
            List<ScarpetLootTables.Entry> actual = new ArrayList<>();
            assertTrue(ScarpetLootTables.expand(alternatives, context, actual::add).join());
            List<ItemStack> nativeItems = new ArrayList<>();
            expected.getFirst().createItemStack(nativeItems::add, context);
            List<ItemStack> actualItems = new ArrayList<>();
            actual.getFirst().produce().apply(stack -> {
                actualItems.add(stack);
                return CompletableFuture.completedFuture(null);
            }).join();
            assertEquals(describe(nativeItems), describe(actualItems));
            assertEquals(Items.DIRT, actualItems.getFirst().getItem());
        }
    }

    @Test
    void trueNativeConsumerChildWaitsBeforeNextRollAndPrivateGlobalEnd() {
        var table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ContextIntProviders.exactly(2)).add(LootItem.lootTableItem(Items.STONE))).build();
        var child = new CompletableFuture<Void>();
        var observed = new ArrayList<ItemStack>();
        try (var owners = new Owners()) {
            var caller = table.carpetGetRandomItemsAsync(params(), 12, stack -> {
                observed.add(stack);
                if (observed.size() == 1) ScarpetNativeWork.record(child);
            });
            assertEquals(1, observed.size());
            assertFalse(caller.isDone());
            caller.cancel(false);
            var drain = ScarpetNativeWork.whenIdle(server);
            assertFalse(drain.isDone());
            child.complete(null);
            drain.join();
            assertEquals(2, observed.size());
        }
    }

    @Test
    void nativeOutputFailureStopsLaterRollsAndReportsRealFailure() {
        var table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ContextIntProviders.exactly(2)).add(LootItem.lootTableItem(Items.STONE))).build();
        var outputs = new java.util.concurrent.atomic.AtomicInteger();
        try (var owners = new Owners()) {
            var result = table.carpetGetRandomItemsAsync(params(), 12, stack -> {
                outputs.incrementAndGet();
                throw new IllegalStateException("real output failure");
            });
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, result::join)));
            assertEquals(1, outputs.get());
        }
    }

    @Test
    void sourceSetContainerContentsProducesBeforeFollowingEntryCondition() {
        var condition = mock(LootItemCondition.class);
        var producer = mock(LootItemFunction.class);
        var context = context(66);
        var order = new ArrayList<String>();
        when(producer.apply(any(), eq(context))).thenAnswer(call -> {
            order.add("first modifier");
            return call.getArgument(0);
        });
        when(condition.test(context)).thenAnswer(call -> {
            order.add("second condition");
            return true;
        });
        var function = SetContainerContents.setContents(ContainerComponentManipulators.CONTAINER)
                .withEntry(LootItem.lootTableItem(Items.STONE).apply(Holder.direct(producer)))
                .withEntry(LootItem.lootTableItem(Items.DIRT).when(Holder.direct(condition))).build();
        try (var owners = new Owners()) {
            ScarpetLootFunctions.apply(function, new ItemStack(Items.CHEST), context).join();
            assertEquals(List.of("first modifier", "second condition"), order);
        }
    }

    @Test
    void sourceSingleNumericNativeFunctionKeepsVanillaStackMutation() {
        var function = SetItemCountFunction.setCount(ContextIntProviders.between(1, 10), true).build();
        try (var owners = new Owners()) {
            for (long seed = 1; seed < 25; ++seed) {
                var expected = function.apply(new ItemStack(Items.STONE, 7), context(seed));
                var actual = ScarpetLootFunctions.apply(function, new ItemStack(Items.STONE, 7), context(seed)).join();
                assertTrue(ItemStack.matches(expected, actual), "native function oracle seed " + seed);
            }
        }
    }

    @Test
    void actualNumericRandomConditionReadsProviderBeforeOriginalRandom() {
        var probability = net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProviders.between(0.1F, 0.9F);
        var condition = new LootItemRandomChanceCondition(probability);
        try (var owners = new Owners()) {
            for (long seed = 1; seed < 40; ++seed)
                assertEquals(condition.test(context(seed)), ScarpetLootConditions.test(condition, context(seed)).join());
        }
    }

    @Test
    void actualRangeSlotSourceReadsAndCopiesForeignSlotsOnTheirOwnerWithCapturedNativeFlags() throws Exception {
        var foreignWorld = mock(ServerLevel.class);
        when(foreignWorld.getServer()).thenReturn(server);
        var foreign = mock(Entity.class);
        when(foreign.level()).thenReturn(foreignWorld);
        when(foreign.blockPosition()).thenReturn(BlockPos.ZERO);
        var wrapper = mock(org.bukkit.craftbukkit.entity.CraftEntity.class);
        when(foreign.getBukkitEntity()).thenReturn(wrapper);
        var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
        field.setAccessible(true);
        field.set(wrapper, scheduler);
        var queued = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Entity>>();
        when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
            queued.set(call.getArgument(0));
            return true;
        });
        var owns = new java.util.concurrent.atomic.AtomicBoolean();
        var range = net.minecraft.world.inventory.SlotRange.of("fixture", it.unimi.dsi.fastutil.ints.IntList.of(0));
        var constructor = net.minecraft.world.item.slot.RangeSlotSource.class.getDeclaredConstructor(LootContextArg.class, net.minecraft.world.inventory.SlotRange.class);
        constructor.setAccessible(true);
        var source = constructor.newInstance(LootContextArg.of(LootContextParams.THIS_ENTITY), range);
        when(foreign.getSlotsFromRange(range.slots())).thenAnswer(call -> {
            assertTrue(owns.get(), "live slots are read on original foreign owner");
            assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
            return net.minecraft.world.item.slot.SlotCollection.of(net.minecraft.world.entity.SlotAccess.of(() -> {
                assertTrue(owns.get());
                return new ItemStack(Items.STONE, 4);
            }, stack -> fail("loot copies never write donor inventory")));
        });
        var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).withParameter(LootContextParams.THIS_ENTITY, foreign).create(LootContextParamSets.CHEST);
        var context = new LootContext.Builder(params).withOptionalRandomSeed(9).create(Optional.empty());
        try (var owners = new Owners()) {
            owners.ticks.when(() -> TickThread.isTickThreadFor(foreign)).thenAnswer(call -> owns.get());
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
            CompletableFuture<CompletableFuture<List<ItemStack>>> actual;
            try {
                actual = ScarpetNativeWork.observeNative(null, () -> ScarpetLootSlots.copies(source, context));
            } finally {
                ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
            }
            assertFalse(actual.isDone());
            verify(foreign, never()).getSlotsFromRange(any());
            owns.set(true);
            queued.get().accept(foreign);
            owns.set(false);
            var copies = actual.thenCompose(value -> value).join();
            assertEquals(4, copies.getFirst().getCount());
            assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
        }
    }

    @Test
    void actualNativeCopyNbtFunctionWaitsOriginalEntityNbtBeforeApplyingRealPathOperation() {
        var entity = mock(Entity.class);
        var tag = new CompletableFuture<net.minecraft.nbt.CompoundTag>();
        var function = CopyCustomDataFunction.copyData(LootContext.EntityTarget.THIS).copy("source_marker", "target_marker").build();
        var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).withParameter(LootContextParams.THIS_ENTITY, entity).create(LootContextParamSets.CHEST);
        var context = new LootContext.Builder(params).withOptionalRandomSeed(3).create(Optional.empty());
        try (var owners = new Owners(); var nbt = mockStatic(ScarpetPredicateNbt.class)) {
            nbt.when(() -> ScarpetPredicateNbt.tag(entity)).thenReturn(tag);
            var result = ScarpetLootFunctions.apply(function, new ItemStack(Items.STONE), context);
            assertFalse(result.isDone());
            var original = new net.minecraft.nbt.CompoundTag();
            original.putInt("source_marker", 73);
            tag.complete(original);
            var data = result.join().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            assertEquals(73, data.getIntOr("target_marker", 0));
        }
    }

    @Test
    void guestOnlyOutputFailureCompletesTrueNativeNextRollWhileOriginalParentKeepsFailure() {
        var table = LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ContextIntProviders.exactly(2)).add(LootItem.lootTableItem(Items.STONE))).build();
        var guest = new CompletableFuture<Void>();
        var observed = new ArrayList<ItemStack>();
        try (var owners = new Owners()) {
            var parent = ScarpetNativeWork.observeNative(null, () -> table.carpetGetRandomItemsAsync(params(), 12, stack -> {
                observed.add(stack);
                if (observed.size() == 1) ScarpetNativeWork.recordGuest(guest);
            }));
            assertFalse(parent.isDone());
            guest.completeExceptionally(new IllegalArgumentException("real VM guest failure"));
            assertEquals(2, observed.size());
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
        }
    }

}
