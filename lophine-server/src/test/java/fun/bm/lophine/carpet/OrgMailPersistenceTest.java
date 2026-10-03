package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgMailPersistenceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors;
        final ArrayDeque<Runnable> files=new ArrayDeque<>();
        final java.lang.reflect.Field executor;
        final Object previous;
        final org.mockito.MockedStatic<OrgFakePlayerActions> actions;
        final OrgMailService mail;
        Fixture()throws Exception {
            actors=new OrgInventoryPersistenceTest.Fixture(directory);executor=OrgMailService.class.getDeclaredField("fileExecutor");executor.setAccessible(true);previous=executor.get(null);executor.set(null,(java.util.concurrent.Executor)files::add);
            org.mockito.MockedStatic<OrgFakePlayerActions> created=null;
            try{
                var io=actors.coordinator.getClass().getDeclaredField("fileActors");io.setAccessible(true);io.set(actors.coordinator,(java.util.concurrent.Executor)files::add);
                when(actors.viewer.player().getScoreboardName()).thenReturn("sender");when(actors.target.player().getScoreboardName()).thenReturn("recipient");
                for(var actor:List.of(actors.viewer,actors.target)){
                    when(actor.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
                    var source=mock(net.minecraft.commands.CommandSourceStack.class);var world=actor.player().level();when(source.getServer()).thenReturn(actors.server);when(source.getEntity()).thenReturn(actor.player());when(source.getPlayer()).thenReturn(actor.player());when(source.getLevel()).thenReturn(world);when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);when(actor.player().createCommandSourceStack()).thenReturn(source);
                }
                when(actors.server.getPlayerList().getPlayerByName(anyString())).thenAnswer(call->switch((String)call.getArgument(0)){case "sender"->actors.viewer.player();case "recipient"->actors.target.player();default->null;});
                when(actors.viewer.player().getMainHandItem()).thenAnswer(call->actors.viewer.inventory().getItem(0));when(actors.viewer.player().getOffhandItem()).thenAnswer(call->actors.viewer.inventory().getItem(40));
                created=mockStatic(OrgFakePlayerActions.class,CALLS_REAL_METHODS);created.when(()->OrgFakePlayerActions.whenIdle(any(ServerPlayer.class),any())).thenAnswer(call->ScarpetPlayerInventoryGate.whenIdle(call.getArgument(0),(Supplier<?>)call.getArgument(1)));
                actions=created;mail=OrgMailService.get(actors.server);
            }catch(Throwable error){if(created!=null)created.close();executor.set(null,previous);actors.close();throw error;}
        }
        void fileWork(){actors.owner.set(null);while(!files.isEmpty())files.remove().run();}
        void pump()throws Exception {for(int pass=0;pass<12;pass++){fileWork();actors.process(actors.viewer);actors.process(actors.target);}fileWork();}
        Path parcel(int id){return directory.resolve(OrgMailService.relative(id));}
        @Override public void close()throws Exception {try{actions.close();}finally{executor.set(null,previous);actors.close();}}
    }
    @Test void singleHandSendAndRecipientCollectUseTheRealDurableFileAndBothInventoryActors()throws Exception {
        try(var fixture=new Fixture()){
            var sent=fixture.mail.send(fixture.actors.viewer.player(),new NameAndId(fixture.actors.target.id(),"recipient"));fixture.pump();assertTrue(sent.join());assertTrue(fixture.actors.viewer.inventory().getItem(0).isEmpty());
            CompoundTag doc=NbtIo.read(fixture.parcel(1));assertEquals("sender",doc.getStringOr("sender",""));assertEquals("recipient",doc.getStringOr("recipient",""));assertEquals(10,OrgMailService.count(OrgMailService.items(fixture.actors.server,doc,fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE))));
            var collected=fixture.mail.take(fixture.actors.target.player(),1,OrgMailService.Operation.COLLECT);fixture.pump();assertTrue(collected.join());
            assertEquals(10,fixture.actors.target.inventory().getNonEquipmentItems().stream().filter(stack->stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum());assertTrue(NbtIo.read(fixture.parcel(1)).getListOrEmpty("items").isEmpty());
        }
    }
    @Test void partialCollectionLeavesExactlyTheRemainingParcelAndRecallLocksItEvenWithoutCapacity()throws Exception {
        try(var fixture=new Fixture()){
            var ops=fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE);Path file=fixture.parcel(1);Files.createDirectories(file.getParent());NbtIo.write(OrgMailService.document("sender","recipient",fixture.actors.target.id(),List.of(new ItemStack(Items.DIAMOND,10)),ops),file);
            for(int slot=0;slot<36;slot++)fixture.actors.target.inventory().setItem(slot,new ItemStack(Items.EMERALD,64));fixture.actors.target.inventory().setItem(0,new ItemStack(Items.DIAMOND,60));
            var collected=fixture.mail.take(fixture.actors.target.player(),1,OrgMailService.Operation.COLLECT);fixture.pump();assertTrue(collected.join());assertEquals(64,fixture.actors.target.inventory().getItem(0).getCount());assertEquals(6,OrgMailService.count(OrgMailService.items(fixture.actors.server,NbtIo.read(file),ops)));
            for(int slot=0;slot<36;slot++)fixture.actors.viewer.inventory().setItem(slot,new ItemStack(Items.EMERALD,64));
            var recall=fixture.mail.take(fixture.actors.viewer.player(),1,OrgMailService.Operation.RECALL);fixture.pump();assertTrue(recall.join());assertTrue(NbtIo.read(file).getBooleanOr("recall",false));assertEquals(6,OrgMailService.count(OrgMailService.items(fixture.actors.server,NbtIo.read(file),ops)));
            var recalledNotice=fixture.mail.take(fixture.actors.target.player(),1,OrgMailService.Operation.COLLECT);fixture.pump();assertTrue(recalledNotice.join());assertEquals(6,OrgMailService.count(OrgMailService.items(fixture.actors.server,NbtIo.read(file),ops)));
        }
    }
    @Test void readbackFailureAfterParcelPublicationCannotRefundTheHandOrExposeADuplicate()throws Exception {
        try(var fixture=new Fixture()){
            var first=new AtomicBoolean(true);Path file=fixture.parcel(1);
            try(var io=mockStatic(NbtIo.class,CALLS_REAL_METHODS)){
                io.when(()->NbtIo.read(file)).thenAnswer(call->{CompoundTag actual=(CompoundTag)call.callRealMethod();if(actual!=null&&!actual.getListOrEmpty("items").isEmpty()&&first.getAndSet(false))throw new IOException("real parcel write readback unavailable");return actual;});
                var sent=fixture.mail.send(fixture.actors.viewer.player(),new NameAndId(fixture.actors.target.id(),"recipient"));fixture.fileWork();fixture.actors.process(fixture.actors.viewer);fixture.actors.process(fixture.actors.viewer);fixture.fileWork();
                assertFalse(sent.isDone());assertTrue(fixture.actors.viewer.inventory().getItem(0).isEmpty());assertEquals(1,NbtIo.read(file).getListOrEmpty("items").size());
                fixture.pump();assertTrue(sent.join());assertTrue(fixture.actors.viewer.inventory().getNonEquipmentItems().stream().noneMatch(stack->stack.is(Items.DIAMOND)));
            }
        }
    }
    @Test void originalV1SingleItemAndCancelKeysUpgradeWithoutChangingTheExistingDocument()throws Exception {
        try(var fixture=new Fixture()){
            var ops=fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE);var original=new CompoundTag();original.putString("sender","sender");original.putString("recipient","recipient");original.putBoolean("cancel",true);original.putInt("NbtDataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());original.put("item",ItemStack.CODEC.encodeStart(ops,new ItemStack(Items.DIAMOND,9)).getOrThrow());
            var upgraded=OrgMailService.format(original);assertTrue(upgraded.getBooleanOr("recall",false));assertEquals(3,upgraded.getIntOr("data_version",0));assertEquals(9,OrgMailService.count(OrgMailService.items(fixture.actors.server,original,ops)));assertTrue(original.contains("item"));assertFalse(original.contains("items"));
        }
    }
}
