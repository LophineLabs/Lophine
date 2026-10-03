package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgPlayerInventoryNativeControlsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();for(var item:java.util.List.of(Items.APPLE,Items.SHULKER_BOX)){try{item.builtInRegistryHolder().components();}catch(NullPointerException unbound){item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,64).build());}}}
    private static void prepare(OrgInventoryPersistenceTest.Fixture fixture)throws Exception{
        for(var actor:fixture.actors.values()){var player=actor.player();when(player.blockPosition()).thenReturn(BlockPos.ZERO);when(player.getDisplayName()).thenReturn(Component.literal(actor==fixture.viewer?"Alice":"Bob"));player.connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);fixture.owner.set(player);var pack=net.minecraft.server.level.ServerPlayer.class.getField("carpetActionPack");pack.setAccessible(true);pack.set(player,new CarpetPlayerActionPack(player));var field=AbstractContainerMenu.class.getDeclaredField("slots");field.setAccessible(true);field.set(player.containerMenu,NonNullList.<Slot>create());}
        fixture.owner.set(null);
    }
    private static AbstractContainerMenu remote(OrgInventoryPersistenceTest.Fixture fixture,boolean gca)throws Exception{
        boolean prior=GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle;GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle=gca;
        try{var viewer=fixture.viewer.player();var opened=new AtomicReference<AbstractContainerMenu>();when(viewer.openMenu(any())).thenAnswer(call->{assertSame(viewer,fixture.owner.get());MenuProvider provider=call.getArgument(0);AbstractContainerMenu menu=provider.createMenu(9,fixture.viewer.inventory(),viewer);viewer.containerMenu=menu;opened.set(menu);return OptionalInt.of(9);});var result=OrgPlayerInventoryMenus.openAsync(viewer,fixture.target.player(),false);for(int pass=0;pass<12&&!result.isDone();pass++){fixture.drain(fixture.target);fixture.drain(fixture.viewer);}assertTrue(result.join());return opened.get();}finally{GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle=prior;}
    }
    @Test void aRealNativePlayerPackAndInventoryGateProduceAStableTrueSnapshot()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);var player=fixture.target.player();fixture.owner.set(player);var old=new CompletableFuture<Void>();ScarpetPlayerInventoryGate.trackAccepted(player,old);var result=new AtomicReference<CompletableFuture<Integer>>();var parent=ScarpetNativeWork.observeNative(null,()->{result.set(OrgFakePlayerActions.whenIdle(player,()->{assertSame(player,fixture.owner.get());return player.getInventory().getItem(0).getCount();}));return null;});assertFalse(parent.isDone());assertFalse(result.get().isDone());assertTrue(result.get().cancel(false));fixture.target.inventory().getItem(0).setCount(23);old.complete(null);for(int pass=0;pass<8&&!parent.isDone();pass++)fixture.drain(fixture.target);parent.join();assertTrue(result.get().isCancelled());assertFalse(ScarpetPlayerInventoryGate.paused(player));ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
    @Test void realHumanRemoteOpenAndGcaCloseWaitActualNativeChildren()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);AbstractContainerMenu menu=remote(fixture,true);var target=fixture.target.player();var child=new CompletableFuture<Void>();doAnswer(call->{assertSame(target,fixture.owner.get());ScarpetNativeWork.record(child);return null;}).when(target).closeContainer();fixture.owner.set(fixture.viewer.player());var parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->{menu.clicked(10,1,ContainerInput.PICKUP,fixture.viewer.player());return null;});verify(target,never()).closeContainer();fixture.drain(fixture.target);verify(target).closeContainer();assertFalse(parent.isDone());assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());child.complete(null);fixture.drain(fixture.viewer);parent.join();for(int pass=0;pass<10;pass++){fixture.drain(fixture.target);fixture.drain(fixture.viewer);}ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
    @Test void aCausalRefreshRunsAsAnActualExternalSnapshotAndWaitsViewerPacketChildren()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);AbstractContainerMenu menu=remote(fixture,false);var packet=new CompletableFuture<Void>();var listener=mock(ContainerListener.class);doAnswer(call->{assertSame(fixture.viewer.player(),fixture.owner.get());ScarpetNativeWork.record(packet);return null;}).when(listener).slotChanged(eq(menu),anyInt(),any(ItemStack.class),any(ItemStack.class));fixture.owner.set(fixture.viewer.player());menu.addSlotListener(listener);fixture.target.inventory().getItem(0).setCount(29);Method refresh=menu.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);var parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->{try{refresh.invoke(menu);}catch(Exception failure){throw new RuntimeException(failure);}return null;});parent.join();assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());for(int pass=0;pass<10;pass++){fixture.drain(fixture.viewer);fixture.drain(fixture.target);}assertEquals(29,menu.getSlot(0).getItem().getCount());assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());packet.complete(null);for(int pass=0;pass<6;pass++){fixture.drain(fixture.viewer);fixture.drain(fixture.target);}ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
    @Test void actualGcaShiftMovePrefersTheOffhandForNativeFood()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);AbstractContainerMenu menu=remote(fixture,true);var food=new ItemStack(Items.APPLE,3);food.set(DataComponents.FOOD,new net.minecraft.world.food.FoodProperties(4,2F,true));fixture.viewer.inventory().setItem(9,food);fixture.owner.set(fixture.viewer.player());ItemStack before=menu.quickMoveStack(fixture.viewer.player(),54);assertTrue(before.isEmpty());assertTrue(fixture.viewer.inventory().getItem(9).isEmpty());assertEquals(3,menu.getSlot(7).getItem().getCount());assertTrue(menu.getSlot(18).getItem().isEmpty());
        }
    }
    @Test void actualSortUsesSourceNonemptySingleTypeBoxPriorityAndPreservesStackIdentity()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);var inventory=fixture.target.inventory();inventory.clearContent();var empty=new ItemStack(Items.SHULKER_BOX);var mixed=new ItemStack(Items.SHULKER_BOX);mixed.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND,2),new ItemStack(Items.EMERALD,3))));var single=new ItemStack(Items.SHULKER_BOX);single.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND,4))));inventory.setItem(9,empty);inventory.setItem(10,mixed);inventory.setItem(11,single);fixture.owner.set(fixture.target.player());var method=OrgPlayerInventoryMenus.class.getDeclaredMethod("sort",net.minecraft.server.level.ServerPlayer.class);method.setAccessible(true);method.invoke(null,fixture.target.player());assertSame(single,inventory.getItem(9));assertSame(mixed,inventory.getItem(10));assertSame(empty,inventory.getItem(11));
        }
    }
    @Test void gcaNativeControlFailureStopsTheViewerRefreshTail()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);AbstractContainerMenu menu=remote(fixture,true);var target=fixture.target.player();var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(target).closeContainer();fixture.owner.set(fixture.viewer.player());var parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->{menu.clicked(10,1,ContainerInput.PICKUP,fixture.viewer.player());return null;});fixture.drain(fixture.target);child.completeExceptionally(new IllegalStateException("actual GCA close native failure"));fixture.drain(fixture.viewer);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.CompletionException.class,parent::join)));var refreshing=menu.getClass().getDeclaredField("refreshing");refreshing.setAccessible(true);assertFalse(refreshing.getBoolean(menu));
        }
    }
    @Test void aRealHumanMandatorySnapshotWaitsOldTerminationAndPreservesCancellationIsolation()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);var player=fixture.target.player();fixture.owner.set(player);var old=new CompletableFuture<Void>();ScarpetPlayerInventoryGate.trackAccepted(player,old);var snapshot=OrgFakePlayerActions.whenIdleForRemoval(player,()->{assertSame(player,fixture.owner.get());return player.getInventory().getItem(0).getCount();});assertFalse(snapshot.isDone());old.completeExceptionally(new IllegalStateException("old native action terminated"));for(int pass=0;pass<8&&!snapshot.isDone();pass++)fixture.drain(fixture.target);assertEquals(20,snapshot.join());assertFalse(ScarpetPlayerInventoryGate.paused(player));
        }
    }
}
