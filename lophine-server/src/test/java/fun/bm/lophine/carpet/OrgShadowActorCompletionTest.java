package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgShadowActorCompletionTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    private record Held(UUID transaction,List<OrgItemShadowGroups.Change> changes) {}
    private Held hold(ItemStack stack) {
        OrgItemShadowGroups.share(stack);
        var preview=new OrgItemShadowGroups.Preview();preview.copies(List.of(stack));
        var held=new Held(UUID.randomUUID(),preview.changes());
        assertTrue(OrgItemShadowGroups.prepare(held.transaction,held.changes,false));return held;
    }
    @Test void aQueuedAliasIntentAllowsEscrowCompletionThenItsAdmittedNativeTailBlocksLaterSnapshots() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player=fixture.viewer.player();var stack=fixture.target.inventory().getItem(0);var held=hold(stack);
            var accept=new AtomicInteger();var realTail=new CompletableFuture<Void>();fixture.owner.set(player);
            assertFalse(OrgItemShadowGroups.actor(player,List.of(stack),()->{stack.shrink(3);ScarpetNativeWork.record(realTail);return true;},false,owned->true,value->accept.incrementAndGet()));
            var escrow=ScarpetPlayerInventoryGate.whenIdle(player,()->{assertEquals(0,accept.get());OrgItemShadowGroups.finish(held.transaction,held.changes,true);return stack.getCount();});
            fixture.drain(fixture.viewer);assertEquals(20,escrow.join());assertEquals(17,stack.getCount());assertEquals(1,accept.get());
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,stack::getCount);fixture.drain(fixture.viewer);assertFalse(snapshot.isDone());
            realTail.complete(null);fixture.drain(fixture.viewer);assertEquals(17,snapshot.join());
            OrgItemShadowGroups.forget(held.transaction,held.changes);
        }
    }
    @Test void aNewStandaloneReleaseWaitsUntilTheInventorySnapshotHasReallyCompleted() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player=fixture.viewer.player();var stack=fixture.target.inventory().getItem(0);OrgItemShadowGroups.share(stack);
            var snapshotTail=new CompletableFuture<Void>();var accept=new AtomicInteger();fixture.owner.set(player);
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,()->snapshotTail);
            assertFalse(OrgItemShadowGroups.actor(player,List.of(stack),()->{stack.shrink(3);return true;},false,owned->true,value->accept.incrementAndGet()));
            fixture.drain(fixture.viewer);assertEquals(20,stack.getCount());assertEquals(0,accept.get());assertFalse(snapshot.isDone());
            snapshotTail.complete(null);fixture.drain(fixture.viewer);assertEquals(17,stack.getCount());assertEquals(1,accept.get());assertTrue(snapshot.isDone());
        }
    }
    @Test void aChangedItemCancelsTheDeferredIntentAndReleasesTheRealCompletion() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player=fixture.viewer.player();var stack=fixture.target.inventory().getItem(0);var held=hold(stack);fixture.owner.set(player);
            assertFalse(OrgItemShadowGroups.actor(player,List.of(stack),()->{fail("Invalid release must not execute");return true;},false,owned->false,value->fail("Invalid release must not complete a native tail")));
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,stack::getCount);fixture.drain(fixture.viewer);fixture.drain(fixture.viewer);
            assertEquals(20,snapshot.join());OrgItemShadowGroups.finish(held.transaction,held.changes,true);OrgItemShadowGroups.forget(held.transaction,held.changes);
        }
    }
}
