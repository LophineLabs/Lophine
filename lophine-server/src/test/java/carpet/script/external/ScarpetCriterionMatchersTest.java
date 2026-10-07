package carpet.script.external;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.minecraft.advancements.*;
import net.minecraft.advancements.predicates.*;
import net.minecraft.advancements.triggers.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

public class ScarpetCriterionMatchersTest {
    @BeforeAll static void bootstrap() { ScarpetLootTablesTest.bootstrap(); }
    final MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
    final ServerLevel world = mock(ServerLevel.class);
    final ServerPlayer player = mock(ServerPlayer.class);
    final PlayerAdvancements advancements = mock(PlayerAdvancements.class);
    final Vec3 origin = new Vec3(9, 70, 11);
    final RandomSource originalRandom = RandomSource.create(19);
    @BeforeEach void setup() {
        when(world.getServer()).thenReturn(server); when(world.getRandom()).thenReturn(originalRandom);
        when(player.carpetSpawnServer()).thenReturn(server); when(player.level()).thenReturn(world);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO); when(player.position()).thenReturn(origin);
        when(player.getAdvancements()).thenReturn(advancements);
    }
    private org.mockito.MockedStatic<TickThread> owners() {
        var owners = mockStatic(TickThread.class);
        owners.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true); return owners;
    }
    private <T extends SimpleCriterionTrigger.SimpleInstance> PlayerAdvancements.TriggerInstanceKey entry(SimpleCriterionTrigger<T> trigger, T value) {
        var key = new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), "actual");
        when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, value)); return key;
    }
    private Holder<LootItemCondition> leaf(java.util.function.Function<LootContext, Boolean> test) {
        var condition = mock(LootItemCondition.class); when(condition.test(any())).thenAnswer(call -> test.apply(call.getArgument(0))); return Holder.direct(condition);
    }
    private Entity target(LootContext context) {
        assertSame(world, context.getLevel()); assertSame(origin, context.getOptional(LootContextParams.ORIGIN));
        assertSame(originalRandom, context.getRandom()); assertTrue(ScarpetLootRandomOwners.bound(context));
        return context.getOptional(LootContextParams.THIS_ENTITY);
    }
    @Test void realBredHeadKeepsChildThenOriginalPairThenSwappedPairAndWaitsAwardChildren() {
        var parent = mock(Animal.class); var partner = mock(Animal.class); var child = mock(AgeableMob.class);
        var order = new ArrayList<String>(); var conditionChild = new CompletableFuture<Void>(); var awardChild = new CompletableFuture<Void>();
        var childPredicate = leaf(context -> { assertSame(child, target(context)); order.add("child"); ScarpetNativeWork.record(conditionChild); return true; });
        var parentPredicate = leaf(context -> { Entity actual = target(context); order.add(actual == parent ? "parent original" : "parent swapped"); return actual == partner; });
        var partnerPredicate = leaf(context -> { assertSame(parent, target(context)); order.add("partner swapped"); return true; });
        var trigger = new BredAnimalsTrigger(); var key = entry(trigger, new BredAnimalsTrigger.TriggerInstance(Optional.empty(), Optional.of(parentPredicate), Optional.of(partnerPredicate), Optional.of(childPredicate)));
        when(advancements.award(key.advancement(), "actual")).thenAnswer(call -> { order.add("award"); ScarpetNativeWork.record(awardChild); return true; });
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, parent, partner, child); return null; });
            assertEquals(List.of("child"), order); assertFalse(actual.isDone());
            var drain = ScarpetNativeWork.whenIdle(server); assertFalse(drain.isDone()); conditionChild.complete(null);
            assertEquals(List.of("child", "parent original", "parent swapped", "partner swapped", "award"), order); assertFalse(actual.isDone());
            awardChild.complete(null); actual.join(); drain.join();
        }
    }
    @Test void absentRequiredBredChildSkipsBothAdultPairs() {
        var parent = mock(Animal.class); var partner = mock(Animal.class); var forbidden = leaf(context -> { fail("adult predicates must short circuit"); return true; });
        var childPredicate = leaf(context -> { fail("no child context exists"); return true; }); var trigger = new BredAnimalsTrigger();
        entry(trigger, new BredAnimalsTrigger.TriggerInstance(Optional.empty(), Optional.of(forbidden), Optional.of(forbidden), Optional.of(childPredicate)));
        try (var owners = owners()) { ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, parent, partner, null); return null; }).join(); }
        verify(advancements, never()).award(any(), anyString());
    }
    @Test void realChanneledHeadMatchesEachPredicateAgainstVictimsInOrderAndStopsAtFirstMatch() {
        var first = mock(Entity.class); var second = mock(Entity.class); var third = mock(Entity.class); var order = new ArrayList<String>(); var child = new CompletableFuture<Void>();
        var a = leaf(context -> { Entity actual = target(context); order.add(actual == first ? "a first" : "a second"); if (actual == first) { ScarpetNativeWork.record(child); return false; } assertSame(second, actual); return true; });
        var b = leaf(context -> { assertSame(first, target(context)); order.add("b first"); return true; }); var trigger = new ChanneledLightningTrigger();
        var key = entry(trigger, new ChanneledLightningTrigger.TriggerInstance(Optional.empty(), List.of(a, b)));
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, List.of(first, second, third)); return null; });
            assertEquals(List.of("a first"), order); assertFalse(actual.isDone()); child.complete(null); actual.join();
            assertEquals(List.of("a first", "a second", "b first"), order); verify(advancements).award(key.advancement(), "actual");
        }
    }
    @Test void realLightningHeadWaitsBoltBeforeOrderedBystanders() {
        var bolt = mock(LightningBolt.class); var first = mock(Entity.class); var second = mock(Entity.class); var third = mock(Entity.class); var order = new ArrayList<String>(); var child = new CompletableFuture<Void>();
        var boltPredicate = leaf(context -> { assertSame(bolt, target(context)); order.add("bolt"); ScarpetNativeWork.record(child); return true; });
        var bystander = leaf(context -> { Entity actual = target(context); order.add(actual == first ? "first" : "second"); assertNotSame(third, actual); return actual == second; });
        var trigger = new LightningStrikeTrigger(); var key = entry(trigger, new LightningStrikeTrigger.TriggerInstance(Optional.empty(), Optional.of(boltPredicate), Optional.of(bystander)));
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, bolt, List.of(first, second, third)); return null; });
            assertEquals(List.of("bolt"), order); child.complete(null); actual.join(); assertEquals(List.of("bolt", "first", "second"), order);
            verify(advancements).award(key.advancement(), "actual");
        }
    }
    @Test void realCuredSummonedTamedAndTargetHeadsHoldTheirActualEntityConditions() {
        var zombie = mock(Zombie.class); var villager = mock(Villager.class); var animal = mock(Animal.class); var projectile = mock(Entity.class);
        var order = new ArrayList<Entity>(); var child = new CompletableFuture<Void>();
        var predicate = leaf(context -> { Entity actual = target(context); order.add(actual); if (actual == zombie) ScarpetNativeWork.record(child); return true; });
        var cured = new CuredZombieVillagerTrigger(); entry(cured, new CuredZombieVillagerTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate), Optional.of(predicate)));
        var summoned = new SummonedEntityTrigger(); entry(summoned, new SummonedEntityTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        var tamed = new TameAnimalTrigger(); entry(tamed, new TameAnimalTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        var target = new TargetBlockTrigger(); entry(target, new TargetBlockTrigger.TriggerInstance(Optional.empty(), MinMaxBounds.Ints.exactly(7), Optional.of(predicate)));
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { cured.trigger(player, zombie, villager); return null; });
            assertEquals(List.of(zombie), order); child.complete(null); actual.join();
            ScarpetNativeWork.observeNative(player, () -> { summoned.trigger(player, projectile); tamed.trigger(player, animal); target.trigger(player, projectile, origin, 6); return null; }).join();
            assertEquals(List.of(zombie, villager, projectile, animal), order);
            ScarpetNativeWork.observeNative(player, () -> { target.trigger(player, projectile, origin, 7); return null; }).join();
            assertEquals(List.of(zombie, villager, projectile, animal, projectile), order);
        }
    }
    @Test void realEffectsHeadChecksActualPlayerEffectsBeforeSourceAndRejectsMissingRequiredSource() {
        var source = mock(Entity.class); var effect = mock(MobEffectsPredicate.class); when(effect.matches(player)).thenReturn(false);
        var predicate = leaf(context -> { fail("effects false skips source"); return true; }); var trigger = new EffectsChangedTrigger();
        entry(trigger, new EffectsChangedTrigger.TriggerInstance(Optional.empty(), Optional.of(effect), Optional.of(predicate)));
        try (var owners = owners()) { ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, source); return null; }).join(); }
        verify(effect).matches(player); verify(advancements, never()).award(any(), anyString());
        entry(trigger, new EffectsChangedTrigger.TriggerInstance(Optional.empty(), Optional.empty(), Optional.of(predicate)));
        try (var owners = owners()) { ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, null); return null; }).join(); }
        verify(advancements, never()).award(any(), anyString());
    }
    @Test void distanceAndExplosionFallKeepOriginalWorldAndPositionWhileSpatialPredicateIsPending() {
        var location = mock(LocationPredicate.class); var start = new Vec3(9, 40, 11); var waiting = new CompletableFuture<Boolean>();
        var distance = DistancePredicate.vertical(MinMaxBounds.Doubles.exactly(30)); var trigger = new DistanceTrigger();
        var key = entry(trigger, new DistanceTrigger.TriggerInstance(Optional.empty(), Optional.of(location), Optional.of(distance)));
        var cause = mock(Entity.class); var predicate = leaf(context -> { assertSame(cause, target(context)); return true; }); var fall = new FallAfterExplosionTrigger();
        var fallKey = entry(fall, new FallAfterExplosionTrigger.TriggerInstance(Optional.empty(), Optional.of(location), Optional.of(distance), Optional.of(predicate)));
        try (var owners = owners(); var spatial = mockStatic(ScarpetLocationPredicates.class)) {
            spatial.when(() -> ScarpetLocationPredicates.matches(location, world, start)).thenReturn(waiting);
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, start); fall.trigger(player, start, cause); return null; });
            assertFalse(actual.isDone()); var movedWorld = mock(ServerLevel.class); when(movedWorld.getServer()).thenReturn(server);
            when(player.level()).thenReturn(movedWorld); when(player.position()).thenReturn(Vec3.ZERO);
            waiting.complete(true); actual.join(); verify(advancements).award(key.advancement(), "actual"); verify(advancements).award(fallKey.advancement(), "actual");
        }
    }
    @Test void allThreeBlockHeadsReadOwnedOriginalBlockThenOriginalToolIdentityAndCallerRandom() {
        var pos = new BlockPos(65, 72, -80); var tool = new ItemStack(Items.STONE); var state = Blocks.STONE.defaultBlockState();
        var order = new ArrayList<String>(); var child = new CompletableFuture<Void>(); var owned = new boolean[1];
        when(world.getBlockState(pos)).thenAnswer(call -> { assertTrue(owned[0]); order.add("state"); return state; });
        when(world.getBlockEntity(pos)).thenAnswer(call -> { assertTrue(owned[0]); order.add("block entity"); return null; });
        var predicate = leaf(context -> { assertSame(world, context.getLevel()); assertSame(player, context.getOptional(LootContextParams.THIS_ENTITY));
            assertEquals(Vec3.atCenterOf(pos), context.getOptional(LootContextParams.ORIGIN)); assertSame(state, context.getOptional(LootContextParams.BLOCK_STATE));
            assertSame(originalRandom, context.getRandom()); assertTrue(ScarpetLootRandomOwners.bound(context));
            if (context.hasParameter(LootContextParams.TOOL)) assertSame(tool, context.getOptional(LootContextParams.TOOL));
            order.add("condition"); ScarpetNativeWork.record(child); return true; });
        var used = new ItemUsedOnLocationTrigger(); entry(used, new ItemUsedOnLocationTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        var any = new AnyBlockInteractionTrigger(); entry(any, new AnyBlockInteractionTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        var defaults = new DefaultBlockInteractionTrigger(); entry(defaults, new DefaultBlockInteractionTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        try (var owners = owners(); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            leases.when(() -> CarpetRegionLease.runLoadedValue(eq(world), eq(3), eq(-6), eq(5), eq(-4), any(Function.class))).thenAnswer(call -> {
                owned[0] = true; try { return CompletableFuture.completedFuture(((Function<?, ?>)call.getArgument(5)).apply(null)); } finally { owned[0] = false; }
            });
            var actual = ScarpetNativeWork.observeNative(player, () -> { used.trigger(player, pos, tool); any.trigger(player, pos, tool); defaults.trigger(player, pos); return null; });
            assertFalse(actual.isDone()); assertEquals(List.of("state", "block entity", "condition", "state", "block entity", "condition", "state", "condition"), order);
            verify(advancements, never()).award(any(), anyString()); child.complete(null); actual.join(); verify(advancements, times(3)).award(any(), anyString());
            leases.verify(() -> CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)), never());
        }
    }
    @Test void privateSubtypeReceiptAndGlobalDrainKeepActualWorkDespiteExternalCancellation() {
        var trigger = new SummonedEntityTrigger(); var entity = mock(Entity.class); var child = new CompletableFuture<Void>();
        var predicate = leaf(context -> { ScarpetNativeWork.record(child); return true; }); var key = entry(trigger, new SummonedEntityTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        try (var owners = owners()) {
            var actual = ScarpetCriterionMatchers.summoned(trigger, player, entity); assertFalse(actual.cancel(true)); var drain = ScarpetNativeWork.whenIdle(server);
            assertFalse(actual.isDone()); assertFalse(drain.isDone()); child.complete(null); actual.join(); drain.join(); verify(advancements).award(key.advancement(), "actual");
        }
    }
    @Test void genuineNativeSubtypeConditionFailureStopsAwardsAndGuestOnlyFailureRetainsPhysicalMatchValue() {
        var trigger = new SummonedEntityTrigger(); var entity = mock(Entity.class); var child = new CompletableFuture<Void>();
        var predicate = leaf(context -> { ScarpetNativeWork.record(child); return true; }); var key = entry(trigger, new SummonedEntityTrigger.TriggerInstance(Optional.empty(), Optional.of(predicate)));
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, entity); return null; });
            child.completeExceptionally(new IllegalStateException("native predicate child")); assertThrows(CompletionException.class, actual::join); verify(advancements, never()).award(any(), anyString());
        }
        var guestChild = new CompletableFuture<Void>(); var guest = new IllegalArgumentException("guest predicate child"); ScarpetNativeWork.markGuestFailure(guest);
        var guestPredicate = leaf(context -> { ScarpetNativeWork.record(guestChild); return true; }); entry(trigger, new SummonedEntityTrigger.TriggerInstance(Optional.empty(), Optional.of(guestPredicate)));
        try (var owners = owners()) {
            var actual = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, entity); return null; }); guestChild.completeExceptionally(guest);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, actual::join))); verify(advancements).award(any(), eq("actual"));
        }
    }
    @Test void registrationFailureCompletesBothPrivateReceiptsInsteadOfHangingTheirNativeParent() {
        when(player.carpetSpawnServer()).thenThrow(new IllegalStateException("server registration stopped"));
        var trigger = new SummonedEntityTrigger(); var entity = mock(Entity.class);
        var typed = ScarpetNativeWork.observeNative(player, () -> { ScarpetNativeWork.record(ScarpetCriterionMatchers.summoned(trigger, player, entity)); return null; });
        assertTrue(typed.isDone()); assertThrows(CompletionException.class, typed::join);
        var generic = ScarpetNativeWork.observeNative(player, () -> { ScarpetNativeWork.record(trigger.carpetTriggerNativeAsync(player, value -> CompletableFuture.completedFuture(true))); return null; });
        assertTrue(generic.isDone()); assertThrows(CompletionException.class, generic::join);
    }
}
