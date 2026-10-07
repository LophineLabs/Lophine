package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgShadowDurableReceiptTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private record Started(ItemStack original,UUID group,UUID transaction,List<OrgItemShadowGroups.Change> changes,Path ledger){}
    private Started start(OrgInventoryPersistenceTest.Fixture fixture)throws Exception{
        ItemStack original=fixture.target.inventory().getItem(0);UUID group=OrgItemShadowGroups.share(original);fixture.target.inventory().setItem(1,original);
        fixture.viewer.inventory().setItem(0,OrgItemShadowGroups.materialize(OrgItemShadowGroups.snapshot(original)));
        fixture.owner.set(fixture.viewer.player());var before=OrgInventoryTransfers.viewerState(fixture.viewer.player());fixture.owner.set(fixture.target.player());var targetBefore=OrgInventoryTransfers.targetState(fixture.target.player(),false);
        var preview=new OrgItemShadowGroups.Preview();var viewer=preview.copies(before);var target=preview.copies(targetBefore);viewer.set(2,target.getFirst().split(3));
        fixture.owner.set(fixture.viewer.player());assertTrue(OrgInventoryTransfers.submit(fixture.viewer.player(),fixture.target.id(),false,targetBefore,OrgInventoryTransfers.copies(target),before,OrgInventoryTransfers.copies(viewer),List.of(),preview.changes(),()->{}));fixture.owner.set(null);
        fixture.process(fixture.viewer);fixture.process(fixture.target);
        Path ledger;try(var files=Files.list(directory.resolve("carpet-org-inventory-escrow"))){ledger=files.filter(file->file.getFileName().toString().endsWith(".nbt")).findFirst().orElseThrow();}
        UUID id=UUID.fromString(ledger.getFileName().toString().replace(".nbt",""));return new Started(original,group,id,preview.changes(),ledger);
    }
    private void finishWithUnretiredLedger(OrgInventoryPersistenceTest.Fixture fixture,Started started)throws Exception{
        try(var io=mockStatic(Files.class,CALLS_REAL_METHODS)){
            io.when(()->Files.deleteIfExists(started.ledger)).thenThrow(new IOException("ledger retirement temporarily unavailable"));fixture.process(fixture.viewer);
        }
        assertEquals("complete",NbtIo.readCompressed(started.ledger,NbtAccounter.unlimitedHeap()).getStringOr("phase",""));assertEquals(17,started.original.getCount());
    }
    @SuppressWarnings("unchecked") private static void simulateColdGroup(UUID id)throws Exception{
        var field=OrgItemShadowGroups.class.getDeclaredField("GROUPS");field.setAccessible(true);((Map<UUID,?>)field.get(null)).remove(id);
    }
    @Test void coldCompletedLedgerRecoveryCannotReplaceNewerSavedFourteenWithOldSeventeenOrTwenty()throws Exception{
        Started started;CompoundTag newer;UUID viewer;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            started=start(fixture);viewer=fixture.viewer.id();finishWithUnretiredLedger(fixture,started);started.original.shrink(3);
            fixture.owner.set(fixture.viewer.player());fixture.storage.save(fixture.viewer.player());newer=fixture.saved.get(viewer).copy();fixture.owner.set(null);
            assertEquals(14,((CompoundTag)newer.getListOrEmpty("Inventory").getFirst()).getIntOr("count",-1));
            assertFalse(((CompoundTag)newer.getListOrEmpty("CarpetOrgEscrowShadows").getFirst()).getListOrEmpty("completed").isEmpty());
        }
        simulateColdGroup(started.group);
        try(var cold=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            // This constructor runs the real persisted ledger loader and canonical file reader.
            var before=OrgItemShadowGroups.restore(started.group,0,new ItemStack(Items.EMERALD,20));var live=OrgItemShadowGroups.materialize(before);assertEquals(14,live.getCount());
            OrgItemShadowGroups.recoverComplete(started.transaction,started.changes,true,true);assertEquals(14,live.getCount());
            var ops=cold.lookup.createSerializationContext(NbtOps.INSTANCE);var descriptor=(CompoundTag)newer.getListOrEmpty("CarpetOrgEscrowShadows").getFirst();
            assertEquals(14,OrgItemShadowGroups.materialize(OrgShadowInventoryCodec.descriptor(descriptor,ops)).getCount());
        }
    }
    @Test void unknownCanonicalReadbackKeepsLiveAssetsHeldUntilTheActualFileIsVerified()throws Exception{
        var tasks=new ArrayDeque<Runnable>();var executor=OrgItemShadowGroups.class.getDeclaredField("journalExecutor");executor.setAccessible(true);Object previous=executor.get(null);executor.set(null,(java.util.concurrent.Executor)tasks::add);
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            Started started=start(fixture);finishWithUnretiredLedger(fixture,started);started.original.shrink(3);Path canonical=directory.resolve("carpet-org-item-shadow-receipts").resolve(started.group+".nbt");var fail=new AtomicBoolean(true);
            try(var io=mockStatic(NbtIo.class,CALLS_REAL_METHODS)){
                io.when(()->NbtIo.readCompressed(eq(canonical),any(NbtAccounter.class))).thenAnswer(call->{CompoundTag actual=(CompoundTag)call.callRealMethod();if(actual.getCompoundOrEmpty("state").getIntOr("count",-1)==14&&fail.getAndSet(false))throw new IOException("canonical readback unavailable");return actual;});
                assertThrows(IllegalStateException.class,()->OrgItemShadowGroups.persistLatest(started.original));assertFalse(OrgItemShadowGroups.attempt(List.of(started.original),()->{fail("Held assets cannot be spent");return true;}).completed());
                assertEquals(14,started.original.getCount());assertEquals(14,NbtIo.readCompressed(canonical,NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("state").getIntOr("count",-1));
                while(!tasks.isEmpty())tasks.remove().run();assertTrue(OrgItemShadowGroups.attempt(List.of(started.original),()->true).completed());assertEquals(14,started.original.getCount());
            }
        }finally{executor.set(null,previous);}
    }
    @Test void aStaleActiveLedgerCannotAcquireNewerCanonicalStockAndRollItBack()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            Started started=start(fixture);finishWithUnretiredLedger(fixture,started);started.original.shrink(3);OrgItemShadowGroups.persistLatest(started.original);
            assertFalse(OrgItemShadowGroups.prepare(UUID.randomUUID(),started.changes,true));assertEquals(14,started.original.getCount());assertTrue(OrgItemShadowGroups.attempt(List.of(started.original),()->true).completed());
        }
    }
    @Test void aZeroCountLiveAliasKeepsItsDurableIdentityAndDoesNotRecreateStockOnColdRestore()throws Exception{
        Started started;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            started=start(fixture);finishWithUnretiredLedger(fixture,started);started.original.setCount(0);OrgItemShadowGroups.persistLatest(started.original);assertTrue(started.original.isEmpty());
        }
        simulateColdGroup(started.group);
        try(var cold=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            ItemStack live=OrgItemShadowGroups.materialize(OrgItemShadowGroups.restore(started.group,0,new ItemStack(Items.EMERALD,20)));assertTrue(live.isEmpty());assertEquals(0,live.getCount());assertNotNull(live.carpetOrgShadowAnchor);
        }
    }
    @Test void anUnchangedCommitDoesNotAdvanceItsCanonicalRevision()throws Exception{
        ItemStack stack=new ItemStack(Items.EMERALD,5);OrgItemShadowGroups.share(stack);var preview=new OrgItemShadowGroups.Preview();preview.copies(List.of(stack));var changes=preview.changes();UUID transaction=UUID.randomUUID();long revision=OrgItemShadowGroups.revision(stack);
        assertTrue(OrgItemShadowGroups.prepare(transaction,changes,false));OrgItemShadowGroups.finish(transaction,changes,true);assertEquals(revision,OrgItemShadowGroups.revision(stack));OrgItemShadowGroups.forget(transaction,changes);
    }
}
