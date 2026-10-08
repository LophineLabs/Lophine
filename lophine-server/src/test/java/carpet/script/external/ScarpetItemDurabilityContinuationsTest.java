package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.ConsumeItemTrigger;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.ItemDurabilityTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetItemDurabilityContinuationsTest {
    @BeforeAll
    static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
    }

    final MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
    final ServerLevel world = mock(ServerLevel.class);
    final ServerPlayer player = mock(ServerPlayer.class);
    final PlayerAdvancements advancements = mock(PlayerAdvancements.class);
    final ItemStack original = new ItemStack(Items.STONE);
    final Vec3 origin = new Vec3(9, 70, 11);

    @BeforeEach
    void setup() {
        when(world.getServer()).thenReturn(server);
        when(world.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(19));
        when(player.carpetSpawnServer()).thenReturn(server);
        when(player.level()).thenReturn(world);
        when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(player.position()).thenReturn(origin);
        when(player.getAdvancements()).thenReturn(advancements);
        original.set(DataComponents.MAX_DAMAGE, 10);
        original.set(DataComponents.DAMAGE, 8);
    }

    private org.mockito.MockedStatic<TickThread> owners() {
        var t = mockStatic(TickThread.class);
        t.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
        return t;
    }

    private PlayerAdvancements.TriggerInstanceKey key(String name) {
        return new PlayerAdvancements.TriggerInstanceKey(mock(AdvancementHolder.class), name);
    }

    private ItemDurabilityTrigger.TriggerInstance entry(Optional<Holder<LootItemCondition>> playerCondition) {
        return new ItemDurabilityTrigger.TriggerInstance(playerCondition, Optional.empty(), MinMaxBounds.Ints.exactly(5), MinMaxBounds.Ints.exactly(3));
    }

    @Test
    void realNativeItemRepairRetainsOldMatcherValueUntilAsyncPlayerConditionAndRewardsBeforePhysicalDamageTail() {
        var predicate = new EntityPredicate(Map.of());
        var condition = new LootItemEntityPropertyCondition(Optional.of(predicate), LootContext.EntityTarget.THIS);
        var first = key("one");
        var second = key("two");
        var map = new LinkedHashMap<PlayerAdvancements.TriggerInstanceKey, ItemDurabilityTrigger.TriggerInstance>();
        map.put(first, entry(Optional.of(Holder.direct(condition))));
        map.put(second, entry(Optional.empty()));
        when(advancements.getTriggerMapForType(CriteriaTriggers.ITEM_DURABILITY_CHANGED)).thenReturn(map);
        var matches = new CompletableFuture<Boolean>();
        var awardChild = new CompletableFuture<Void>();
        var order = new ArrayList<String>();
        when(advancements.award(first.advancement(), "one")).thenAnswer(c -> {
            assertEquals(8, original.getDamageValue());
            order.add("award first");
            ScarpetNativeWork.record(awardChild);
            return true;
        });
        when(advancements.award(second.advancement(), "two")).thenAnswer(c -> {
            assertEquals(8, original.getDamageValue());
            order.add("award second");
            return true;
        });
        try (var t = owners(); var predicates = mockStatic(ScarpetEntityPredicates.class)) {
            predicates.when(() -> ScarpetEntityPredicates.matches(predicate, world, origin, player)).thenReturn(matches);
            var parent = ScarpetNativeWork.observeNative(player, () -> {
                original.hurtWithoutBreaking(-3, player);
                return null;
            });
            assertEquals(8, original.getDamageValue());
            assertFalse(parent.isDone());
            verify(advancements, never()).award(any(), anyString());
            matches.complete(true);
            assertEquals(List.of("award first"), order);
            assertEquals(8, original.getDamageValue());
            assertFalse(parent.isDone());
            awardChild.complete(null);
            parent.join();
            assertEquals(List.of("award first", "award second"), order);
            assertEquals(5, original.getDamageValue());
        }
    }

    @Test
    void genericConsumeItemNativeHeadWaitsTruePlayerConditionRatherThanProvisionalFalse() {
        var trigger = new ConsumeItemTrigger();
        var predicate = new EntityPredicate(Map.of());
        var condition = new LootItemEntityPropertyCondition(Optional.of(predicate), LootContext.EntityTarget.THIS);
        var key = key("consume");
        when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, new ConsumeItemTrigger.TriggerInstance(Optional.of(Holder.direct(condition)), Optional.empty())));
        var conditionActual = new CompletableFuture<Boolean>();
        try (var t = owners(); var predicates = mockStatic(ScarpetEntityPredicates.class)) {
            predicates.when(() -> ScarpetEntityPredicates.matches(predicate, world, origin, player)).thenReturn(conditionActual);
            var parent = ScarpetNativeWork.observeNative(player, () -> {
                trigger.trigger(player, original);
                return null;
            });
            assertFalse(parent.isDone());
            verify(advancements, never()).award(any(), anyString());
            conditionActual.complete(true);
            parent.join();
            verify(advancements).award(key.advancement(), "consume");
        }
    }

    @Test
    void privateNativeTriggerCannotBeCanceledWhileActualAwardOrGlobalDrainIsPending() {
        var trigger = new ItemDurabilityTrigger();
        var key = key("item");
        when(advancements.getTriggerMapForType(trigger)).thenReturn(Map.of(key, entry(Optional.empty())));
        var child = new CompletableFuture<Void>();
        when(advancements.award(key.advancement(), "item")).thenAnswer(c -> {
            ScarpetNativeWork.record(child);
            return true;
        });
        try (var t = owners()) {
            var actual = trigger.carpetTriggerNativeAsync(player, value -> CompletableFuture.completedFuture(value.matches(original, 5)));
            assertFalse(actual.cancel(false));
            assertFalse(actual.isDone());
            var drain = ScarpetNativeWork.whenIdle(server);
            assertFalse(drain.isDone());
            child.complete(null);
            actual.join();
            drain.join();
        }
    }

    @Test
    void realNativeAwardFailureStopsOriginalItemDamageTail() {
        var key = key("item");
        when(advancements.getTriggerMapForType(CriteriaTriggers.ITEM_DURABILITY_CHANGED)).thenReturn(Map.of(key, entry(Optional.empty())));
        var child = new CompletableFuture<Void>();
        when(advancements.award(key.advancement(), "item")).thenAnswer(c -> {
            ScarpetNativeWork.record(child);
            return true;
        });
        try (var t = owners()) {
            var parent = ScarpetNativeWork.observeNative(player, () -> {
                original.hurtWithoutBreaking(-3, player);
                return null;
            });
            child.completeExceptionally(new IllegalStateException("real reward failure"));
            assertThrows(CompletionException.class, parent::join);
            assertEquals(8, original.getDamageValue());
        }
    }

    @Test
    void guestRewardFailureContinuesActualOriginalItemTailWhileParentRetainsFailure() {
        var key = key("item");
        when(advancements.getTriggerMapForType(CriteriaTriggers.ITEM_DURABILITY_CHANGED)).thenReturn(Map.of(key, entry(Optional.empty())));
        var child = new CompletableFuture<Void>();
        when(advancements.award(key.advancement(), "item")).thenAnswer(c -> {
            ScarpetNativeWork.record(child);
            return true;
        });
        var guest = new IllegalArgumentException("real guest failure");
        ScarpetNativeWork.markGuestFailure(guest);
        try (var t = owners()) {
            var parent = ScarpetNativeWork.observeNative(player, () -> {
                original.hurtWithoutBreaking(-3, player);
                return null;
            });
            child.completeExceptionally(guest);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
            assertEquals(5, original.getDamageValue());
        }
    }
}
