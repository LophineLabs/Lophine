package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrgInventoryEscrowTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // These unit fixtures run without production data-pack component loading.
        for (var item : List.of(Items.COBBLESTONE, Items.DIAMOND, Items.GOLD_INGOT, Items.EMERALD, Items.STONE, Items.DIAMOND_SWORD)) {
            try { item.builtInRegistryHolder().components(); }
            catch (NullPointerException unbound) { item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, item == Items.DIAMOND_SWORD ? 1 : 64).build()); }
        }
    }

    private static List<ItemStack> empty() { return new ArrayList<>(Collections.nCopies(44, ItemStack.EMPTY)); }
    private static List<ItemStack> full() {
        List<ItemStack> result = empty();
        for (int slot = 0; slot < 36; slot++) result.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        return result;
    }

    @Test void refundPreservesSubsequentEquipmentAndCursorWritesWhenInventoryIsFull() {
        List<ItemStack> before = empty(), wanted = empty(), current = full();
        before.set(39, new ItemStack(Items.DIAMOND, 7));
        current.set(39, new ItemStack(Items.GOLD_INGOT, 3));
        current.set(43, new ItemStack(Items.EMERALD, 4));
        var plan = OrgInventoryTransfers.credit(current, before, wanted, true, false);
        assertTrue(OrgInventoryTransfers.same(current, plan.after()));
        assertEquals(7, plan.remaining().getFirst().getCount());
        assertTrue(plan.remaining().getFirst().is(Items.DIAMOND));
        assertEquals(7, before.get(39).getCount());
    }

    @Test void creditFindsCurrentCapacityWithoutOverwritingAChangedCursor() {
        List<ItemStack> before = empty(), wanted = empty(), current = empty();
        before.set(0, new ItemStack(Items.DIAMOND, 7));
        wanted.set(43, new ItemStack(Items.EMERALD, 4));
        current.set(0, new ItemStack(Items.COBBLESTONE, 5));
        current.set(43, new ItemStack(Items.GOLD_INGOT, 2));
        var plan = OrgInventoryTransfers.credit(current, before, wanted, false, true);
        assertEquals(5, plan.after().get(0).getCount());
        assertEquals(2, plan.after().get(43).getCount());
        assertTrue(plan.after().get(1).is(Items.EMERALD));
        assertEquals(4, plan.after().get(1).getCount());
        assertTrue(plan.remaining().isEmpty());
        assertTrue(current.get(1).isEmpty());
    }

    @Test void partialDeliveryRetainsExactRemainderAndRespectsLaterConsumption() {
        List<ItemStack> current = full();
        current.set(5, new ItemStack(Items.EMERALD, 62));
        List<ItemStack> pending = new ArrayList<>(List.of(new ItemStack(Items.EMERALD, 9)));
        OrgInventoryTransfers.insert(current, pending);
        assertEquals(64, current.get(5).getCount());
        assertEquals(7, pending.getFirst().getCount());
        current.get(5).shrink(10);
        OrgInventoryTransfers.insert(current, pending);
        assertEquals(61, current.get(5).getCount());
        assertTrue(pending.isEmpty());
    }

    @Test void aDifferentComponentSetCannotBeMergedOrReplaced() {
        List<ItemStack> current = full();
        ItemStack occupied = new ItemStack(Items.DIAMOND, 63), incoming = new ItemStack(Items.DIAMOND, 1);
        occupied.set(DataComponents.CUSTOM_NAME, Component.literal("new legitimate stack"));
        incoming.set(DataComponents.CUSTOM_NAME, Component.literal("held in escrow"));
        current.set(0, occupied);
        List<ItemStack> pending = new ArrayList<>(List.of(incoming));
        OrgInventoryTransfers.insert(current, pending);
        assertEquals(63, current.getFirst().getCount());
        assertEquals(1, pending.getFirst().getCount());
        assertNotEquals(current.getFirst().get(DataComponents.CUSTOM_NAME), pending.getFirst().get(DataComponents.CUSTOM_NAME));
    }

    @Test void closedMenuCreditGoesToInventoryInsteadOfAnUnrelatedCursor() {
        List<ItemStack> before = empty(), wanted = empty();
        wanted.set(43, new ItemStack(Items.DIAMOND, 7));
        var plan = OrgInventoryTransfers.credit(empty(), before, wanted, false, false);
        assertTrue(plan.after().get(43).isEmpty());
        assertEquals(7, plan.after().getFirst().getCount());
        assertTrue(plan.remaining().isEmpty());
    }

    @Test void enderCreditCanUseItsFinalSlot() {
        List<ItemStack> current = new ArrayList<>();
        for (int slot = 0; slot < 26; slot++) current.add(new ItemStack(Items.COBBLESTONE, 64));
        current.add(ItemStack.EMPTY);
        List<ItemStack> credit = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 7)));
        OrgInventoryTransfers.insert(current, credit, 27);
        assertEquals(7, current.get(26).getCount());
        assertTrue(current.get(26).is(Items.DIAMOND));
        assertTrue(credit.isEmpty());
    }

    @Test void arbitraryBusinessWritesAndCapacityKeepEveryCreditedItemExactlyOnce() {
        java.util.Random random = new java.util.Random(137L);
        List<ItemStack> kinds = List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD), new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND_SWORD));
        for (int iteration = 0; iteration < 256; iteration++) {
            List<ItemStack> current = empty(), before = empty(), desired = empty();
            for (int slot = 0; slot < 44; slot++) {
                if (random.nextInt(4) != 0) current.set(slot, kinds.get(random.nextInt(kinds.size())).copyWithCount(1 + random.nextInt(64)));
                if (random.nextBoolean()) before.set(slot, kinds.get(random.nextInt(kinds.size())).copyWithCount(1 + random.nextInt(64)));
                if (random.nextBoolean()) desired.set(slot, kinds.get(random.nextInt(kinds.size())).copyWithCount(1 + random.nextInt(64)));
            }
            var plan = OrgInventoryTransfers.credit(current, before, desired, false, random.nextBoolean());
            for (ItemStack kind : kinds) {
                long credited = total(plan.credits(), kind);
                assertEquals(total(current, kind) + credited, total(plan.after(), kind) + total(plan.remaining(), kind), "iteration " + iteration);
            }
            for (int slot = 0; slot < 44; slot++) {
                if (current.get(slot).isEmpty()) continue;
                assertTrue(ItemStack.isSameItemSameComponents(current.get(slot), plan.after().get(slot)));
                assertTrue(plan.after().get(slot).getCount() >= current.get(slot).getCount());
            }
        }
    }

    private static long total(List<ItemStack> stacks, ItemStack kind) {
        return stacks.stream().filter(stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, kind)).mapToLong(ItemStack::getCount).sum();
    }

    @Test void previewDropCustodyIsCopiedAndScopeAlwaysEnds() {
        ItemStack original = new ItemStack(Items.DIAMOND, 3);
        List<ItemStack> drops = OrgInventoryTransfers.preview(() -> assertTrue(OrgInventoryTransfers.captureDrop(original)));
        original.shrink(1);
        assertEquals(3, drops.getFirst().getCount());
        assertFalse(OrgInventoryTransfers.isPreview());
        assertFalse(OrgInventoryTransfers.captureDrop(original));
        assertThrows(IllegalStateException.class, () -> OrgInventoryTransfers.preview(() -> { throw new IllegalStateException("preview failed"); }));
        assertFalse(OrgInventoryTransfers.isPreview());
    }
}
