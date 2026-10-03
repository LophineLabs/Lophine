package carpet.script.external;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.advancements.*;
import net.minecraft.advancements.predicates.*;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.advancements.triggers.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

/** Executes real trigger heads, listener instances and registered Native EntityPredicate parts. */
public class ScarpetCriterionRemainingMatchersTest {
    @BeforeAll static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
        for (var item : List.of(Items.EMERALD, Items.FISHING_ROD, Items.COD, Items.GOLD_INGOT, Items.CROSSBOW)) {
            try { item.builtInRegistryHolder().components(); }
            catch (NullPointerException unbound) { item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build()); }
        }
    }
    private record Task(Entity entity, Consumer<Entity> action) { }
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        final ServerLevel world = mock(ServerLevel.class), moved = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final PlayerAdvancements advancements = mock(PlayerAdvancements.class);
        final Vec3 origin = new Vec3(12, 80, -33);
        final RandomSource random = RandomSource.create(13);
        final Queue<Task> tasks = new ArrayDeque<>();
        final List<String> order = new ArrayList<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        Entity owner;
        Fixture() throws Exception {
            when(world.getServer()).thenReturn(server); when(world.getRandom()).thenReturn(random); when(world.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(moved.getServer()).thenReturn(server); when(moved.getRandom()).thenReturn(RandomSource.create(97));
            when(player.level()).thenReturn(world); when(player.position()).thenReturn(origin); when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            when(player.carpetSpawnServer()).thenReturn(server); when(player.getAdvancements()).thenReturn(advancements);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == owner);
            schedule(player);
        }
        <T extends Entity> T entity(Class<T> type) throws Exception {
            T actual = mock(type); when(actual.level()).thenReturn(moved); when(actual.blockPosition()).thenReturn(new BlockPos(300, 40, 70));
            schedule(actual); return actual;
        }
        void schedule(Entity actual) throws Exception {
            CraftEntity wrapper = actual instanceof ServerPlayer ? mock(org.bukkit.craftbukkit.entity.CraftPlayer.class)
                : actual instanceof LivingEntity ? mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class) : mock(CraftEntity.class);
            when(actual.getBukkitEntity()).thenReturn(wrapper);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = CraftEntity.class.getField("taskScheduler"); field.setAccessible(true); field.set(wrapper, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> { tasks.add(new Task(actual, call.getArgument(0))); return true; });
        }
        <T extends SimpleCriterionTrigger.SimpleInstance> PlayerAdvancements.TriggerInstanceKey entry(SimpleCriterionTrigger<T> trigger, T value) {
            var key = new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), "actual");
            when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, value)); return key;
        }
        net.minecraft.core.Holder<LootItemCondition> entityCondition(EntitySubPredicate actual) {
            return EntityPredicate.wrap(new EntityPredicate(Map.of(EntityFlagsPredicate.CODEC, actual)));
        }
        CompletableFuture<Void> begin(Runnable body) {
            owner = player;
            try { return ScarpetNativeWork.observeNative(player, () -> { body.run(); return null; }); }
            finally { owner = null; }
        }
        void drain() {
            Task task;
            while ((task = tasks.poll()) != null) {
                owner = task.entity(); try { task.action().accept(owner); } finally { owner = null; }
            }
        }
        @Override public void close() { ticks.close(); }
    }
    @Test void pickupAndInteractKeepItemIdentityBeforeActualForeignEntityAndWaitAllNativeChildren() throws Exception {
        try (var f = new Fixture()) {
            var entity = f.entity(Entity.class); var stack = new ItemStack(Items.STONE); var child = new CompletableFuture<Void>();
            var item = mock(ItemPredicate.class);
            when(item.test(stack)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("item"); return true; });
            var predicate = f.entityCondition((actual, world, origin) -> {
                assertSame(entity, actual); assertSame(entity, f.owner); assertSame(f.world, world); assertSame(f.origin, origin);
                f.order.add("entity"); ScarpetNativeWork.record(child); return true;
            });
            var pickup = new PickedUpItemTrigger(); f.entry(pickup, new PickedUpItemTrigger.TriggerInstance(Optional.empty(), Optional.of(item), Optional.of(predicate)));
            var interact = new PlayerInteractTrigger(); f.entry(interact, new PlayerInteractTrigger.TriggerInstance(Optional.empty(), Optional.of(item), Optional.of(predicate)));
            var parent = f.begin(() -> { pickup.trigger(f.player, stack, entity); interact.trigger(f.player, stack, entity); });
            assertEquals(List.of("item", "item"), f.order); f.drain(); assertFalse(parent.isDone());
            verify(f.advancements, never()).award(any(), anyString()); child.complete(null); f.drain(); parent.join();
            assertEquals(List.of("item", "item", "entity", "entity"), f.order); verify(f.advancements, times(2)).award(any(), eq("actual"));
        }
    }
    @Test void falseOriginalItemSkipsForeignEntityConditions() throws Exception {
        try (var f = new Fixture()) {
            var entity = f.entity(Entity.class); var stack = new ItemStack(Items.STONE); var item = mock(ItemPredicate.class);
            var predicate = f.entityCondition((actual, world, origin) -> { fail("item false must skip entity"); return true; });
            var pickup = new PickedUpItemTrigger(); f.entry(pickup, new PickedUpItemTrigger.TriggerInstance(Optional.empty(), Optional.of(item), Optional.of(predicate)));
            var interact = new PlayerInteractTrigger(); f.entry(interact, new PlayerInteractTrigger.TriggerInstance(Optional.empty(), Optional.of(item), Optional.of(predicate)));
            f.begin(() -> { pickup.trigger(f.player, stack, entity); interact.trigger(f.player, stack, entity); }).join();
            assertTrue(f.tasks.isEmpty()); verify(f.advancements, never()).award(any(), anyString());
        }
    }
    @Test void tradeCompletesEveryRealListenerMatchBeforeOriginalOrderedAwards() throws Exception {
        try (var f = new Fixture()) {
            var villager = f.entity(AbstractVillager.class); var stack = new ItemStack(Items.EMERALD); var child = new CompletableFuture<Void>();
            var item = mock(ItemPredicate.class); when(item.test(stack)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("item"); return true; });
            var first = f.entityCondition((actual, world, origin) -> { assertSame(villager, f.owner); f.order.add("first"); ScarpetNativeWork.record(child); return true; });
            var second = f.entityCondition((actual, world, origin) -> { assertSame(villager, f.owner); f.order.add("second"); return true; });
            var trigger = new TradeTrigger();
            var a = new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), "a");
            var b = new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), "b");
            var listeners = new LinkedHashMap<PlayerAdvancements.TriggerInstanceKey, TradeTrigger.TriggerInstance>();
            listeners.put(a, new TradeTrigger.TriggerInstance(Optional.empty(), Optional.of(first), Optional.of(item)));
            listeners.put(b, new TradeTrigger.TriggerInstance(Optional.empty(), Optional.of(second), Optional.of(item)));
            when(f.advancements.getTriggerMapForType(trigger)).thenReturn(listeners);
            when(f.advancements.award(any(), anyString())).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("award " + call.getArgument(1)); return true; });
            var parent = f.begin(() -> trigger.trigger(f.player, villager, stack)); listeners.clear(); f.drain();
            assertEquals(List.of("first"), f.order); assertFalse(parent.isDone()); child.complete(null); f.drain(); parent.join();
            assertEquals(List.of("first", "item", "second", "item", "award a", "award b"), f.order);
        }
    }
    @Test void fishingReadsHookAndItemOnTheirRealOwnersRetainsSourceAndScansCaughtItemsAfterHookedItemMatched() throws Exception {
        try (var f = new Fixture()) {
            var hook = f.entity(FishingHook.class); var hooked = f.entity(ItemEntity.class);
            var rod = new ItemStack(Items.FISHING_ROD); var held = new ItemStack(Items.COD);
            var a = new ItemStack(Items.DIRT); var b = new ItemStack(Items.STONE); var forbidden = new ItemStack(Items.GOLD_INGOT); var child = new CompletableFuture<Void>();
            when(hook.getHookedIn()).thenAnswer(call -> { assertSame(hook, f.owner); f.order.add("hook"); return hooked; });
            when(hooked.getItem()).thenAnswer(call -> { assertSame(hooked, f.owner); f.order.add("get item"); return held; });
            var rodPredicate = mock(ItemPredicate.class); when(rodPredicate.test(rod)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("rod"); return true; });
            var predicate = f.entityCondition((actual, world, origin) -> { assertSame(hooked, f.owner); assertSame(hooked, actual); assertSame(f.world, world); assertSame(f.origin, origin); f.order.add("entity"); return true; });
            var item = mock(ItemPredicate.class);
            when(item.test(held)).thenAnswer(call -> { assertSame(hooked, f.owner); f.order.add("hooked item"); ScarpetNativeWork.record(child); return true; });
            when(item.test(a)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("caught a"); return false; });
            when(item.test(b)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("caught b"); return true; });
            when(item.test(forbidden)).thenAnswer(call -> { fail("caught scan must stop at first match"); return false; });
            var trigger = new FishingRodHookedTrigger(); f.entry(trigger, new FishingRodHookedTrigger.TriggerInstance(Optional.empty(), Optional.of(rodPredicate), Optional.of(predicate), Optional.of(item)));
            var parent = f.begin(() -> trigger.trigger(f.player, rod, hook, List.of(a, b, forbidden)));
            when(f.player.level()).thenReturn(f.moved); when(f.player.position()).thenReturn(Vec3.ZERO);
            f.drain(); assertEquals(List.of("hook", "hook", "rod", "entity", "get item", "hooked item"), f.order); assertFalse(parent.isDone());
            child.complete(null); f.drain(); parent.join();
            assertEquals(List.of("hook", "hook", "rod", "entity", "get item", "hooked item", "caught a", "caught b"), f.order);
            verify(f.advancements).award(any(), eq("actual"));
        }
    }
    @Test void fishingWithoutHookedEntityUsesActualHookAndOnlyOneOriginalGetHookedInRead() throws Exception {
        try (var f = new Fixture()) {
            var hook = f.entity(FishingHook.class); var rod = new ItemStack(Items.FISHING_ROD);
            var predicate = f.entityCondition((actual, world, origin) -> { assertSame(hook, actual); assertSame(hook, f.owner); return true; });
            var trigger = new FishingRodHookedTrigger(); f.entry(trigger, new FishingRodHookedTrigger.TriggerInstance(Optional.empty(), Optional.empty(), Optional.of(predicate), Optional.empty()));
            var parent = f.begin(() -> trigger.trigger(f.player, rod, hook, List.of())); f.drain(); parent.join();
            verify(hook).getHookedIn(); verify(f.advancements).award(any(), eq("actual"));
        }
    }
    @Test void arrowReadsTypesOnEachRealOwnerThenConsumesDistinctVictimsInSourceOrder() throws Exception {
        try (var f = new Fixture()) {
            var first = f.entity(Entity.class); var second = f.entity(Entity.class); var weapon = new ItemStack(Items.CROSSBOW); var typeChild = new CompletableFuture<Void>(); var matchChild = new CompletableFuture<Void>();
            when(first.getType()).thenAnswer(call -> { assertSame(first, f.owner); f.order.add("first type"); ScarpetNativeWork.record(typeChild); return EntityTypes.COW; });
            when(second.getType()).thenAnswer(call -> { assertSame(second, f.owner); f.order.add("second type"); return EntityTypes.PIG; });
            var a = f.entityCondition((actual, world, origin) -> { assertSame(actual, f.owner); assertSame(f.world, world); assertSame(f.origin, origin); f.order.add(actual == first ? "a first" : "a second"); if (actual == second) ScarpetNativeWork.record(matchChild); return actual == second; });
            var b = f.entityCondition((actual, world, origin) -> { assertSame(first, actual); assertSame(first, f.owner); f.order.add("b first"); return true; });
            var item = mock(ItemPredicate.class); when(item.test(weapon)).thenAnswer(call -> { assertSame(f.player, f.owner); f.order.add("weapon"); return true; });
            var trigger = new KilledByArrowTrigger(); f.entry(trigger, new KilledByArrowTrigger.TriggerInstance(Optional.empty(), List.of(a, b), MinMaxBounds.Ints.exactly(2), Optional.of(item)));
            var parent = f.begin(() -> trigger.trigger(f.player, List.of(first, second), weapon)); f.drain(); assertEquals(List.of("first type"), f.order);
            when(f.player.level()).thenReturn(f.moved); when(f.player.position()).thenReturn(Vec3.ZERO);
            typeChild.complete(null); f.drain(); assertEquals(List.of("first type", "second type", "weapon", "a first", "a second"), f.order); assertFalse(parent.isDone());
            matchChild.complete(null); f.drain(); parent.join(); assertEquals(List.of("first type", "second type", "weapon", "a first", "a second", "b first"), f.order);
            verify(f.advancements).award(any(), eq("actual"));
        }
    }
    @Test void arrowCannotReuseOneVictimForTwoRequiredPredicatesAndNullRequiredWeaponSkipsVictimMatchers() throws Exception {
        try (var f = new Fixture()) {
            var victim = f.entity(Entity.class); doReturn(EntityTypes.COW).when(victim).getType(); var count = new int[1];
            var predicate = f.entityCondition((actual, world, origin) -> { ++count[0]; return true; }); var trigger = new KilledByArrowTrigger();
            f.entry(trigger, new KilledByArrowTrigger.TriggerInstance(Optional.empty(), List.of(predicate, predicate), MinMaxBounds.Ints.ANY, Optional.empty()));
            var parent = f.begin(() -> trigger.trigger(f.player, List.of(victim), null)); f.drain(); parent.join(); assertEquals(1, count[0]);
            f.entry(trigger, new KilledByArrowTrigger.TriggerInstance(Optional.empty(), List.of(predicate), MinMaxBounds.Ints.ANY, Optional.of(mock(ItemPredicate.class))));
            parent = f.begin(() -> trigger.trigger(f.player, List.of(victim), null)); f.drain(); parent.join(); assertEquals(1, count[0]);
            verify(f.advancements, never()).award(any(), anyString());
        }
    }
    @Test void nullPickupEntityRetainsOriginalRequiredContextFailureBeforeMatching() throws Exception {
        try (var f = new Fixture()) {
            var trigger = new PickedUpItemTrigger(); f.entry(trigger, new PickedUpItemTrigger.TriggerInstance(Optional.empty(), Optional.empty(), Optional.empty()));
            var parent = f.begin(() -> trigger.trigger(f.player, new ItemStack(Items.STONE), null));
            assertThrows(CompletionException.class, parent::join); verify(f.advancements, never()).award(any(), anyString());
        }
    }
    @Test void genuineNativeEntityPredicateChildFailureStopsTheItemAndAwardTail() throws Exception {
        try (var f = new Fixture()) {
            var villager = f.entity(AbstractVillager.class); var child = new CompletableFuture<Void>(); var item = mock(ItemPredicate.class); var stack = new ItemStack(Items.EMERALD);
            var predicate = f.entityCondition((actual, world, origin) -> { ScarpetNativeWork.record(child); return true; }); var trigger = new TradeTrigger();
            f.entry(trigger, new TradeTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate), Optional.of(item)));
            var parent = f.begin(() -> trigger.trigger(f.player, villager, stack)); f.drain(); assertFalse(parent.isDone());
            child.completeExceptionally(new IllegalStateException("native predicate child")); f.drain(); assertThrows(CompletionException.class, parent::join);
            verify(item, never()).test(any()); verify(f.advancements, never()).award(any(), anyString());
        }
    }
    @Test void guestOnlyEntityPredicateChildFailureRetainsRawParentFailureAndRestoresThePhysicalTrueValue() throws Exception {
        try (var f = new Fixture()) {
            var villager = f.entity(AbstractVillager.class); var child = new CompletableFuture<Void>(); var stack = new ItemStack(Items.EMERALD);
            var predicate = f.entityCondition((actual, world, origin) -> { ScarpetNativeWork.record(child); return true; }); var trigger = new TradeTrigger();
            var item = mock(ItemPredicate.class); when(item.test(stack)).thenReturn(true);
            f.entry(trigger, new TradeTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate), Optional.of(item)));
            var parent = f.begin(() -> trigger.trigger(f.player, villager, stack)); f.drain(); var guest = new IllegalArgumentException("guest predicate child"); ScarpetNativeWork.markGuestFailure(guest);
            child.completeExceptionally(guest); f.drain(); assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
            verify(item).test(stack); verify(f.advancements).award(any(), eq("actual"));
        }
    }
    @Test void matchBlockUsesActualCapturedBlockEntityOwnerAndWaitsItsNativeSaveChild() throws Exception {
        try (var f = new Fixture(); var actors = mockStatic(ScarpetExplosionActors.class, CALLS_REAL_METHODS)) {
            var entity = mock(BlockEntity.class); var position = new BlockPos(321, 60, 432); var tag = new CompoundTag(); tag.putInt("marker", 17);
            when(entity.getBlockPos()).thenReturn(position); var child = new CompletableFuture<Void>(); var owned = new boolean[1];
            when(entity.saveWithFullMetadata(any(net.minecraft.core.HolderLookup.Provider.class))).thenAnswer(call -> { assertTrue(owned[0]); f.order.add("owned save"); ScarpetNativeWork.record(child); return tag; });
            var block = new BlockPredicate(Optional.empty(), Optional.empty(), Optional.of(new NbtPredicate(tag)), DataComponentMatchers.ANY);
            var params = new LootParams.Builder(f.world).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO)).withParameter(LootContextParams.BLOCK_STATE, Blocks.STONE.defaultBlockState())
                .withParameter(LootContextParams.BLOCK_ENTITY, entity).withParameter(LootContextParams.TOOL, new ItemStack(Items.STONE)).create(LootContextParamSets.BLOCK);
            var context = new LootContext.Builder(params).withOptionalRandomSource(f.random).create(Optional.empty());
            ScarpetLootRandomOwners.bind(context, new ScarpetAttackEnchantments.SourceAdmission(f.world, BlockPos.ZERO, f.random, f.player));
            actors.when(() -> ScarpetExplosionActors.world(eq(f.world), eq(position), any())).thenAnswer(call -> {
                owned[0] = true; try { return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(2)).get()); } finally { owned[0] = false; }
            });
            var parent = f.begin(() -> ScarpetNativeWork.record(ScarpetLootConditions.test(new MatchBlock(block), context)));
            assertEquals(List.of("owned save"), f.order); assertFalse(parent.isDone()); child.complete(null); parent.join();
            verify(entity).saveWithFullMetadata(any(net.minecraft.core.HolderLookup.Provider.class)); actors.verify(() -> ScarpetExplosionActors.world(eq(f.world), eq(BlockPos.ZERO), any()), never());
        }
    }
    @Test void genericPlayerContextUsesExactOriginalCallerRandomBindingBeforePredicateAndAward() throws Exception {
        try (var f = new Fixture()) {
            var condition = mock(LootItemCondition.class);
            when(condition.test(any())).thenAnswer(call -> { LootContext context = call.getArgument(0); assertTrue(ScarpetLootRandomOwners.bound(context)); assertSame(f.random, context.getRandom()); assertSame(f.player, context.getOptional(LootContextParams.THIS_ENTITY)); assertSame(f.player, f.owner); return true; });
            var trigger = new StartRidingTrigger(); f.entry(trigger, new StartRidingTrigger.TriggerInstance(Optional.of(Holder.direct(condition))));
            f.begin(() -> trigger.trigger(f.player)).join(); verify(f.advancements).award(any(), eq("actual"));
        }
    }
    @Test void ownedBlockNativeHeadReadsActualBlockDirectlyWithoutLeaseAndWaitsItsChildren() throws Exception {
        try (var f = new Fixture(); var leases = mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class)) {
            var position = new BlockPos(16, 70, 32); var stack = new ItemStack(Items.STONE); var child = new CompletableFuture<Void>();
            f.ticks.when(() -> TickThread.isTickThreadFor(f.world, position)).thenReturn(true);
            when(f.world.getBlockState(position)).thenAnswer(call -> { f.order.add("state"); ScarpetNativeWork.record(child); return Blocks.STONE.defaultBlockState(); });
            when(f.world.getBlockEntity(position)).thenAnswer(call -> { f.order.add("entity"); return null; });
            var condition = mock(LootItemCondition.class); when(condition.test(any())).thenAnswer(call -> {
                LootContext context = call.getArgument(0); assertSame(f.world, context.getLevel()); assertSame(f.random, context.getRandom());
                assertSame(stack, context.getOptional(LootContextParams.TOOL)); f.order.add("condition"); return true;
            });
            var trigger = new ItemUsedOnLocationTrigger(); f.entry(trigger, new ItemUsedOnLocationTrigger.TriggerInstance(Optional.empty(), Optional.of(Holder.direct(condition))));
            var parent = f.begin(() -> trigger.trigger(f.player, position, stack)); assertFalse(parent.isDone()); assertEquals(List.of("state", "entity"), f.order);
            child.complete(null); f.drain(); parent.join(); assertEquals(List.of("state", "entity", "condition"), f.order);
            leases.verifyNoInteractions(); verify(f.advancements).award(any(), eq("actual"));
        }
    }
}
