package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OrgInventoryQuiescentPrepareTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static boolean submit(OrgInventoryPersistenceTest.Fixture fixture, AtomicBoolean finished) {
        fixture.owner.set(fixture.viewer.player());
        var viewerBefore = OrgInventoryTransfers.viewerState(fixture.viewer.player());
        fixture.owner.set(fixture.target.player());
        var targetBefore = OrgInventoryTransfers.targetState(fixture.target.player(), false);
        var preview = new OrgItemShadowGroups.Preview();
        var viewerAfter = preview.copies(viewerBefore);
        var targetAfter = preview.copies(targetBefore);
        viewerAfter.set(0, ItemStack.EMPTY);
        viewerAfter.set(1, targetAfter.getFirst().split(3));
        targetAfter.set(2, new ItemStack(Items.DIAMOND, 10));
        fixture.owner.set(fixture.viewer.player());
        boolean accepted = OrgInventoryTransfers.submit(fixture.viewer.player(), fixture.target.id(), false, targetBefore, OrgInventoryTransfers.copies(targetAfter), viewerBefore, OrgInventoryTransfers.copies(viewerAfter), List.of(), preview.changes(), () -> finished.set(true));
        fixture.owner.set(null);
        return accepted;
    }

    @Test
    void anOldTargetNativeContinuationCanConsumeItsAliasBeforeTheNewTransactionClaimsGroups() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            ItemStack alias = fixture.target.inventory().getItem(0);
            OrgItemShadowGroups.share(alias);
            var old = new CompletableFuture<Void>();
            fixture.owner.set(fixture.target.player());
            var actual = ScarpetNativeWork.<Void>observeNative(fixture.target.player(), () -> {
                ScarpetNativeWork.record(old);
                return null;
            });
            ScarpetPlayerInventoryGate.trackAccepted(fixture.target.player(), actual);
            var finished = new AtomicBoolean();
            assertTrue(submit(fixture, finished));
            fixture.process(fixture.viewer);
            fixture.process(fixture.target);
            assertFalse(finished.get());
            assertFalse(OrgItemShadowGroups.transactionBound(alias));
            assertTrue(OrgInventoryTransfers.participantBlocked(fixture.viewer.player()));
            assertTrue(OrgInventoryTransfers.participantBlocked(fixture.target.player()));
            fixture.owner.set(fixture.target.player());
            try (var approved = ScarpetPlayerInventoryGate.acceptedScope(fixture.target.player())) {
                assertTrue(OrgItemShadowGroups.attempt(List.of(alias), () -> {
                    alias.shrink(3);
                    return true;
                }).completed());
            }
            old.complete(null);
            for (int tick = 0; tick < 8; tick++) {
                fixture.process(fixture.viewer);
                fixture.process(fixture.target);
            }
            assertTrue(finished.get());
            assertEquals(17, alias.getCount());
            assertEquals(10, fixture.viewer.inventory().getItem(0).getCount());
            assertTrue(fixture.viewer.inventory().getItem(0).is(Items.DIAMOND));
            assertFalse(OrgInventoryTransfers.participantBlocked(fixture.viewer.player()));
            assertFalse(OrgItemShadowGroups.transactionBound(alias));
        }
    }

    @Test
    void bothOwnersHoldTheirProducerPauseFromQuiescenceThroughActualCustodyCompletion() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, false, true)) {
            ItemStack alias = fixture.target.inventory().getItem(0);
            OrgItemShadowGroups.share(alias);
            var completed = new AtomicBoolean();
            assertTrue(submit(fixture, completed));
            for (int tick = 0; tick < 4 && !OrgItemShadowGroups.transactionBound(alias); tick++) {
                fixture.process(fixture.viewer);
                fixture.process(fixture.target);
            }
            assertTrue(OrgItemShadowGroups.transactionBound(alias));
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.viewer.player()));
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.target.player()));
            for (int tick = 0; tick < 10 && !completed.get(); tick++) {
                fixture.process(fixture.viewer);
                fixture.process(fixture.target);
            }
            assertTrue(completed.get());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.viewer.player()));
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.target.player()));
            assertEquals(17, alias.getCount());
            assertEquals(3, fixture.viewer.inventory().getItem(1).getCount());
        }
    }
}
