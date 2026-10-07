package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgShadowInventoryTransactionsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }

    private record Started(ItemStack original, UUID group) {}
    private static Started start(OrgInventoryPersistenceTest.Fixture fixture) throws Exception {
        ItemStack original = fixture.target.inventory().getItem(0);
        UUID group = OrgItemShadowGroups.share(original);
        fixture.target.inventory().setItem(1, original);
        fixture.viewer.inventory().setItem(0, OrgItemShadowGroups.materialize(OrgItemShadowGroups.snapshot(original)));
        fixture.owner.set(fixture.viewer.player()); var viewerBefore = OrgInventoryTransfers.viewerState(fixture.viewer.player());
        fixture.owner.set(fixture.target.player()); var targetBefore = OrgInventoryTransfers.targetState(fixture.target.player(), false);
        var preview = new OrgItemShadowGroups.Preview(); var target = preview.copies(targetBefore); var viewer = preview.copies(viewerBefore);
        viewer.set(2, target.get(0).split(3));
        assertEquals(17, viewer.get(0).getCount()); assertEquals(17, target.get(1).getCount());
        fixture.owner.set(fixture.viewer.player());
        assertTrue(OrgInventoryTransfers.submit(fixture.viewer.player(), fixture.target.id(), false, targetBefore, OrgInventoryTransfers.copies(target),
            viewerBefore, OrgInventoryTransfers.copies(viewer), List.of(), preview.changes(), () -> {}));
        fixture.owner.set(null);
        fixture.process(fixture.viewer);
        return new Started(original, group);
    }

    @Test void aSerialCrossOwnerSplitPreservesAllAliasesAndTheSplitCreditDetaches() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            Started started = start(fixture); fixture.process(fixture.target); fixture.process(fixture.viewer);
            assertEquals(17, started.original.getCount());
            assertEquals(started.group, fixture.target.inventory().getItem(0).carpetOrgShadowId);
            assertEquals(started.group, fixture.target.inventory().getItem(1).carpetOrgShadowId);
            assertEquals(started.group, fixture.viewer.inventory().getItem(0).carpetOrgShadowId);
            assertNotSame(fixture.viewer.inventory().getItem(0), fixture.target.inventory().getItem(0));
            assertEquals(3, fixture.viewer.inventory().getItem(2).getCount()); assertNull(fixture.viewer.inventory().getItem(2).carpetOrgShadowId);
            fixture.viewer.inventory().getItem(0).shrink(2);
            assertEquals(15, fixture.target.inventory().getItem(0).getCount()); assertEquals(15, fixture.target.inventory().getItem(1).getCount());
            fixture.viewer.inventory().getItem(2).grow(1); assertEquals(15, started.original.getCount());
        }
    }

    @Test void unknownAliasCreditRemainsHeldAndLaterBusinessItemsSurviveItsRelocation() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            Started started = start(fixture); fixture.unreadable.addAll(List.of(3, 4)); fixture.process(fixture.target);
            assertTrue(fixture.target.inventory().getItem(0).isEmpty()); assertTrue(fixture.target.inventory().getItem(1).isEmpty());
            assertEquals(20, started.original.getCount()); assertTrue(started.original.split(1).isEmpty());
            fixture.target.inventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 3)); fixture.process(fixture.target);
            assertTrue(fixture.target.inventory().getItem(1).isEmpty());
            fixture.process(fixture.target); fixture.process(fixture.viewer);
            assertTrue(fixture.target.inventory().getItem(0).is(Items.GOLD_INGOT)); assertEquals(3, fixture.target.inventory().getItem(0).getCount());
            assertEquals(started.group, fixture.target.inventory().getItem(1).carpetOrgShadowId);
            assertEquals(started.group, fixture.target.inventory().getItem(2).carpetOrgShadowId);
            assertEquals(17, started.original.getCount()); assertEquals(3, fixture.viewer.inventory().getItem(2).getCount());
        }
    }

    @Test void anAutosaveCannotRewriteAnAcknowledgedAfterReceiptWithTheFrozenBeforeCount() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            Started started = start(fixture); fixture.process(fixture.target);
            assertEquals(20, started.original.getCount()); // Global actors cannot spend this quantity until the transaction commits.
            fixture.owner.set(fixture.target.player()); CompoundTag tag = OrgInventoryTransfers.inventoryTag(fixture.target.player());
            var inventory = tag.getListOrEmpty("Inventory");
            assertEquals(17, ((CompoundTag) inventory.get(0)).getIntOr("count", -1));
            assertEquals(17, ((CompoundTag) inventory.get(1)).getIntOr("count", -1));
            assertEquals(2, tag.getListOrEmpty("CarpetOrgEscrowShadows").size());
            assertEquals(20, started.original.getCount()); fixture.owner.set(null);
            fixture.process(fixture.viewer); assertEquals(17, started.original.getCount());
        }
    }

    @Test void trustedDescriptorsRetainAZeroCountTransitionAndKeepEqualUnrelatedItemsSeparate() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            ItemStack first = new ItemStack(Items.EMERALD, 5), other = new ItemStack(Items.EMERALD, 5);
            OrgItemShadowGroups.share(first); OrgItemShadowGroups.share(other);
            var preview = new OrgItemShadowGroups.Preview(); var view = preview.copies(List.of(first, first, other));
            view.get(0).split(5); var changes = preview.changes();
            assertEquals(0, changes.stream().filter(change -> change.id().equals(first.carpetOrgShadowId)).findFirst().orElseThrow().after().carpetOrgOriginalCount());
            CompoundTag tag = new CompoundTag(); var ops = fixture.lookup.createSerializationContext(NbtOps.INSTANCE);
            OrgShadowInventoryCodec.stacks(tag, "stocks", view, ops); var restored = OrgShadowInventoryCodec.stacks(tag, "stocks", ops);
            assertSame(restored.get(0), restored.get(1)); assertEquals(0, restored.get(0).carpetOrgOriginalCount());
            assertEquals(Items.EMERALD, restored.get(0).carpetOrgOriginalHolder().value());
            assertNotEquals(restored.get(0).carpetOrgShadowId, restored.get(2).carpetOrgShadowId);
        }
    }

    @Test void receiptScopesAreReadOnlyAndNativeActionsCannotRunInsideTheirPrivilegedView() {
        ItemStack first = new ItemStack(Items.EMERALD, 5); OrgItemShadowGroups.share(first);
        var preview = new OrgItemShadowGroups.Preview(); var view = preview.copies(List.of(first)); view.getFirst().shrink(1);
        var changes = preview.changes(); UUID transaction = UUID.randomUUID();
        assertTrue(OrgItemShadowGroups.prepare(transaction, changes, false));
        try {
            OrgItemShadowGroups.transaction(transaction, changes, true, () -> {
                assertEquals(4, first.getCount());
                assertFalse(OrgItemShadowGroups.attempt(List.of(first), () -> { fail("Native actions must wait for the receipt to commit"); return true; }).completed());
                assertThrows(IllegalStateException.class, () -> first.grow(1)); return true;
            });
            assertEquals(5, first.getCount()); OrgItemShadowGroups.finish(transaction, changes, true); assertEquals(4, first.getCount());
            first.shrink(1); OrgItemShadowGroups.finish(transaction, changes, true); assertEquals(3, first.getCount());
        } finally { OrgItemShadowGroups.forget(transaction, changes); }
    }

    @Test void retiringAnOwnerLeavesItsDeferredReturnInTheNativePlayerSave() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            Started started = start(fixture); AtomicBoolean returned = new AtomicBoolean();
            fixture.target.inventory().setItem(0, ItemStack.EMPTY); fixture.target.inventory().setItem(1, ItemStack.EMPTY);
            fixture.owner.set(fixture.target.player());
            OrgInventoryTransfers.deferReturn(fixture.target.player(), started.original, () -> returned.set(true));
            assertFalse(returned.get());
            CompoundTag saved = OrgInventoryTransfers.inventoryTag(fixture.target.player());
            assertEquals(1, saved.getListOrEmpty("CarpetOrgEscrowCursor").size());
            assertEquals(20, ((CompoundTag) saved.getListOrEmpty("CarpetOrgEscrowCursor").get(0)).getIntOr("count", -1));
            assertEquals(1, saved.getListOrEmpty("CarpetOrgEscrowShadows").size());
            fixture.owner.set(null); fixture.process(fixture.target); fixture.process(fixture.viewer);
        }
    }
}
