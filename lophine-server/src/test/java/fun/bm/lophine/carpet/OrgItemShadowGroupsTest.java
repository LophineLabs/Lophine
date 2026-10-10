package fun.bm.lophine.carpet;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrgItemShadowGroupsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try {
            Items.STONE.builtInRegistryHolder().components();
        } catch (NullPointerException unbound) {
            Items.STONE.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }

    private static ItemStack alias(ItemStack original) {
        var id = OrgItemShadowGroups.share(original);
        ItemStack result = original.copy(true);
        OrgItemShadowGroups.bind(result, id, false);
        return result;
    }

    @Test
    void aliasesPropagateCountAndComponentsAndExplicitCopiesDetach() {
        ItemStack first = new ItemStack(Items.STONE, 12), second = alias(first);
        first.shrink(3);
        assertEquals(9, second.getCount());
        second.set(DataComponents.DAMAGE, 7);
        assertEquals(7, first.get(DataComponents.DAMAGE));
        ItemStack independent = second.copy();
        assertNull(independent.carpetOrgShadowId);
        independent.grow(1);
        independent.set(DataComponents.DAMAGE, 99);
        assertEquals(9, first.getCount());
        assertEquals(7, first.get(DataComponents.DAMAGE));
        ItemStack split = first.split(2);
        assertEquals(2, split.getCount());
        assertNull(split.carpetOrgShadowId);
        assertEquals(7, second.getCount());
        ItemStack unrelated = new ItemStack(Items.STONE, 7);
        unrelated.set(DataComponents.DAMAGE, 7);
        assertTrue(ItemStack.isSameItemSameComponents(first, unrelated));
        OrgItemShadowGroups.share(unrelated);
        unrelated.shrink(1);
        assertEquals(7, second.getCount());
        assertEquals(6, unrelated.getCount());
    }

    @Test
    void concurrentSplitsCannotSpendTheSameSharedQuantityTwice() throws Exception {
        ItemStack first = new ItemStack(Items.STONE, 6), second = alias(first);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.split(5);
            });
            var b = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.split(5);
            });
            start.countDown();
            assertEquals(6, a.get(5, TimeUnit.SECONDS).getCount() + b.get(5, TimeUnit.SECONDS).getCount());
            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
        }
    }

    @Test
    void componentReadModifyWriteIsAtomicAcrossIndependentActorInstances() throws Exception {
        ItemStack first = new ItemStack(Items.STONE), second = alias(first);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++) first.update(DataComponents.DAMAGE, 0, n -> n + 1);
            });
            var b = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++) second.update(DataComponents.DAMAGE, 0, n -> n + 1);
            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(2_000, first.get(DataComponents.DAMAGE));
            assertEquals(2_000, second.get(DataComponents.DAMAGE));
        }
    }

    @Test
    void aBorrowKeepsForeignViewsNonemptyAndQueuesConflictingActionsWithoutBlocking() throws Exception {
        ItemStack first = new ItemStack(Items.STONE, 6), second = alias(first);
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> OrgItemShadowGroups.attempt(List.of(first), () -> {
                first.shrink(2);
                assertEquals(4, first.getCount());
                prepared.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                return true;
            }));
            try {
                assertTrue(prepared.await(5, TimeUnit.SECONDS));
                assertEquals(6, second.getCount());
                assertFalse(second.isEmpty());
                assertTrue(second.split(5).isEmpty());
                assertFalse(OrgItemShadowGroups.attempt(List.of(second), () -> {
                    fail("A busy actor callback must not run");
                    return false;
                }).completed());
                second.set(DataComponents.MAX_STACK_SIZE, 32); // A later unrelated component write survives publishing.
            } finally {
                release.countDown();
            }
            assertTrue(owner.get(5, TimeUnit.SECONDS).completed());
            assertEquals(4, second.getCount());
            assertEquals(32, first.get(DataComponents.MAX_STACK_SIZE));
        }
    }

    @Test
    void reversedGroupOrdersDoNotDeadlockOrLoseACommittedDelta() throws Exception {
        ItemStack first = new ItemStack(Items.STONE, 10_000), firstAlias = alias(first);
        ItemStack second = new ItemStack(Items.STONE, 10_000), secondAlias = alias(second);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++)
                    OrgItemShadowGroups.attempt(List.of(first, second), () -> {
                        first.shrink(1);
                        second.grow(1);
                        return true;
                    });
            });
            var b = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++)
                    OrgItemShadowGroups.attempt(List.of(secondAlias, firstAlias), () -> {
                        secondAlias.shrink(1);
                        firstAlias.grow(1);
                        return true;
                    });
            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(20_000, first.getCount() + second.getCount());
            assertEquals(first.getCount(), firstAlias.getCount());
            assertEquals(second.getCount(), secondAlias.getCount());
        }
    }

    @Test
    void previewSnapshotsPreserveOnlyExplicitAliasIdentityAndNativeCopiesStillDetach() {
        ItemStack first = new ItemStack(Items.STONE, 8), second = alias(first), unrelated = new ItemStack(Items.STONE, 8);
        var view = OrgItemShadowGroups.snapshots(List.of(first, second, unrelated));
        assertSame(view.get(0), view.get(1));
        assertNotSame(view.get(0), view.get(2));
        assertTrue(view.get(0).carpetOrgShadowDetached);
        view.get(0).shrink(2);
        assertEquals(6, view.get(1).getCount());
        assertEquals(8, first.getCount());
        assertNull(view.get(0).copy().carpetOrgShadowId);
    }
}
