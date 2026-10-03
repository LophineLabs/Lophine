package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.*;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetCriterionContinuationsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    final MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
    final ServerLevel world = mock(ServerLevel.class);
    final ServerPlayer player = mock(ServerPlayer.class);
    final PlayerAdvancements advancements = mock(PlayerAdvancements.class);
    @BeforeEach void setup() {
        when(world.getServer()).thenReturn(server); when(world.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(23)); when(player.carpetSpawnServer()).thenReturn(server);
        when(player.level()).thenReturn(world); when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO); when(player.position()).thenReturn(new Vec3(9, 70, 11));
        when(player.getAdvancements()).thenReturn(advancements);
    }
    private org.mockito.MockedStatic<TickThread> owners() {
        var ticks = mockStatic(TickThread.class); ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true); return ticks;
    }
    private PlayerAdvancements.TriggerInstanceKey key(String name) { return new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), name); }
    private KilledTrigger.TriggerInstance entry() { return new KilledTrigger.TriggerInstance(Optional.empty(), Optional.empty(), Optional.empty()); }
    @Test void nativeListenerEntryMatchesAllBeforeOrderedAwardsAndWaitsRealAwardChildren() {
        KilledTrigger trigger = new KilledTrigger(); var one = key("one"); var two = key("two"); var first = entry(); var second = entry();
        var map = new LinkedHashMap<PlayerAdvancements.TriggerInstanceKey, KilledTrigger.TriggerInstance>(); map.put(one, first); map.put(two, second);
        when(advancements.getTriggerMapForType(trigger)).thenReturn(map);
        var matcherOne = new CompletableFuture<Boolean>(); var matcherTwo = new CompletableFuture<Boolean>(); var awardOne = new CompletableFuture<Void>(); var order = new ArrayList<String>();
        when(advancements.award(one.advancement(), "one")).thenAnswer(call -> { order.add("award one"); ScarpetNativeWork.record(awardOne); return true; });
        when(advancements.award(two.advancement(), "two")).thenAnswer(call -> { order.add("award two"); return true; });
        try (var ticks = owners()) {
            var actual = trigger.carpetTriggerAsync(player, value -> { order.add(value == first ? "match one" : "match two"); return value == first ? matcherOne : matcherTwo; });
            assertEquals(List.of("match one"), order); matcherOne.complete(true); assertEquals(List.of("match one", "match two"), order);
            matcherTwo.complete(true); assertEquals(List.of("match one", "match two", "award one"), order); assertFalse(actual.isDone());
            awardOne.complete(null); actual.join(); assertEquals("award two", order.getLast());
        }
    }
    @Test void canceledCallerCannotFinishNativeAwardOrGlobalDrain() {
        KilledTrigger trigger = new KilledTrigger(); var key = key("one"); when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, entry()));
        var child = new CompletableFuture<Void>(); when(advancements.award(key.advancement(), "one")).thenAnswer(call -> { ScarpetNativeWork.record(child); return true; });
        try (var ticks = owners()) {
            var caller = trigger.carpetTriggerAsync(player, value -> CompletableFuture.completedFuture(true)); caller.cancel(false);
            var drain = ScarpetNativeWork.whenIdle(server); assertFalse(drain.isDone()); child.complete(null); drain.join();
        }
    }
    @Test void genuineAwardFailureStopsLaterAwards() {
        KilledTrigger trigger = new KilledTrigger(); var one = key("one"); var two = key("two");
        var map = new LinkedHashMap<PlayerAdvancements.TriggerInstanceKey, KilledTrigger.TriggerInstance>(); map.put(one, entry()); map.put(two, entry());
        when(advancements.getTriggerMapForType(trigger)).thenReturn(map); when(advancements.award(one.advancement(), "one")).thenThrow(new IllegalStateException("actual award failure"));
        try (var ticks = owners()) {
            var actual = trigger.carpetTriggerAsync(player, value -> CompletableFuture.completedFuture(true));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, actual::join)));
            verify(advancements, never()).award(two.advancement(), "two");
        }
    }
    @Test void actualKilledNativeHeadEvaluatesForeignEntityInOriginalPlayerContextThenAwards() {
        KilledTrigger trigger = new KilledTrigger(); var victim = mock(Entity.class); var predicate = new EntityPredicate(Map.of()); var key = key("one");
        var condition = new LootItemEntityPropertyCondition(Optional.of(predicate), LootContext.EntityTarget.THIS);
        var entry = new KilledTrigger.TriggerInstance(Optional.empty(), Optional.of(Holder.direct(condition)), Optional.empty());
        when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, entry)); var foreign = new CompletableFuture<Boolean>();
        try (var ticks = owners(); var predicates = mockStatic(ScarpetEntityPredicates.class)) {
            predicates.when(() -> ScarpetEntityPredicates.matches(predicate, world, new Vec3(9, 70, 11), victim)).thenReturn(foreign);
            var parent = ScarpetNativeWork.observeNative(player, () -> { trigger.trigger(player, victim, mock(DamageSource.class)); return 7; });
            assertFalse(parent.isDone()); verify(advancements, never()).award(any(), anyString());
            foreign.complete(true); assertEquals(7, parent.join()); verify(advancements).award(key.advancement(), "one");
            predicates.verify(() -> ScarpetEntityPredicates.matches(predicate, world, new Vec3(9, 70, 11), victim));
        }
    }
    @Test void lootCompositeRetainsShortCircuitAndEachRealLeafChildren() {
        var context = mock(LootContext.class); var first = mock(LootItemCondition.class); var second = mock(LootItemCondition.class); var third = mock(LootItemCondition.class);
        var child = new CompletableFuture<Void>(); when(first.test(context)).thenAnswer(call -> { ScarpetNativeWork.record(child); return false; }); when(second.test(context)).thenReturn(true);
        var condition = AnyOfCondition.anyOf(() -> first, () -> second, () -> third).build();
        var actual = ScarpetLootConditions.test(condition, context); assertFalse(actual.isDone()); verifyNoInteractions(second, third);
        child.complete(null); assertTrue(actual.join()); verify(second).test(context); verifyNoInteractions(third);
        var inverse = new InvertedLootItemCondition(Holder.direct(condition)); assertFalse(ScarpetLootConditions.test(inverse, context).join());
    }
    @Test void damageSourceDirectFalseSkipsSourceEntityAndPreservesOriginalWorldOrigin() {
        var direct = new EntityPredicate(Map.of()); var indirect = new EntityPredicate(Map.of()); var directActor = mock(Entity.class); var indirectActor = mock(Entity.class); var source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(directActor); when(source.getEntity()).thenReturn(indirectActor); var origin = new Vec3(4, 5, 6);
        var predicate = new DamageSourcePredicate(List.of(), Optional.of(direct), Optional.of(indirect), Optional.empty());
        try (var predicates = mockStatic(ScarpetEntityPredicates.class)) {
            predicates.when(() -> ScarpetEntityPredicates.matches(direct, world, origin, directActor)).thenReturn(CompletableFuture.completedFuture(false));
            assertFalse(ScarpetLootConditions.damage(predicate, world, origin, source).join());
            predicates.verify(() -> ScarpetEntityPredicates.matches(indirect, world, origin, indirectActor), never());
        }
    }
    @Test void matcherFalseDoesNotConstructOrEvaluateLazyPlayerPredicate() {
        KilledTrigger trigger = new KilledTrigger(); var key = key("one"); var condition = mock(LootItemCondition.class);
        var entry = new KilledTrigger.TriggerInstance(Optional.of(Holder.direct(condition)), Optional.empty(), Optional.empty());
        when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, entry));
        try (var ticks = owners()) {
            trigger.carpetTriggerAsync(player, value -> CompletableFuture.completedFuture(false)).join(); verifyNoInteractions(condition);
            verify(player, never()).position(); verify(advancements, never()).award(any(), anyString());
        }
    }
}
