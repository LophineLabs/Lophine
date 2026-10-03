package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import org.bukkit.craftbukkit.inventory.CraftMerchant;
import org.bukkit.craftbukkit.inventory.view.CraftMerchantView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

class OrgNativeMenuSequenceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private final class TradeFixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors=new OrgInventoryPersistenceTest.Fixture(directory);
        final OrgInventoryPersistenceTest.Actor bot=actors.actor(ServerBot.class);
        final ServerBot player=(ServerBot)bot.player();
        final MerchantMenu menu=mock(MerchantMenu.class);
        final List<String> order=new ArrayList<>();
        final AtomicInteger clicks=new AtomicInteger();
        final CompletableFuture<Void> first=new CompletableFuture<>();
        final CompletableFuture<Void> last=new CompletableFuture<>();
        final org.mockito.MockedStatic<io.papermc.paper.threadedregions.RegionizedServer> global=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);
        TradeFixture() throws Exception {
            when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO); when(actors.viewer.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO); when(actors.target.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            global.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            var view=mock(CraftMerchantView.class);when(menu.getBukkitView()).thenReturn(view);
            var top=mock(org.bukkit.inventory.MerchantInventory.class);when(view.getTopInventory()).thenReturn(top);
            var bukkitMerchant=mock(CraftMerchant.class);when(view.getMerchant()).thenReturn(bukkitMerchant);when(bukkitMerchant.getMerchant()).thenReturn(mock(net.minecraft.world.item.trading.Merchant.class));
            var slots=NonNullList.<Slot>create();var container=new SimpleContainer(3);var diamond=new ItemStack(Items.DIAMOND);
            slots.add(new Slot(container,0,0,0));slots.add(new Slot(container,1,0,0));
            slots.add(new Slot(container,2,0,0){@Override public ItemStack getItem(){return clicks.get()<2?diamond:ItemStack.EMPTY;}});
            var field=AbstractContainerMenu.class.getField("slots");field.setAccessible(true);field.set(menu,slots);
            when(menu.getSlot(anyInt())).thenAnswer(call->slots.get(call.getArgument(0)));
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);player.containerMenu=menu;
            var offer=mock(MerchantOffer.class);when(offer.getCostA()).thenReturn(ItemStack.EMPTY);when(offer.getCostB()).thenReturn(ItemStack.EMPTY);when(offer.getResult()).thenReturn(diamond);
            when(offer.isOutOfStock()).thenAnswer(call->clicks.get()>=2);var offers=new MerchantOffers();offers.add(offer);when(menu.getOffers()).thenReturn(offers);
            doAnswer(call->{assertSame(player,actors.owner.get());assertTrue(OrgGameplayHelper.insideOrgAction());int number=clicks.incrementAndGet();order.add("click "+number);ScarpetNativeWork.record(number==1?first:last);return null;}).when(menu).clicked(2,0,ContainerInput.THROW,player);
            doAnswer(call->{assertSame(player,actors.owner.get());order.add("close");return null;}).when(player).closeContainer();
            doAnswer(call->{assertSame(player,actors.owner.get());order.add("broadcast");return null;}).when(menu).broadcastChanges();
            doAnswer(call->{assertSame(player,actors.owner.get());order.add("equipment");return null;}).when(player).detectEquipmentUpdates();
            actors.owner.set(player);OrgFakePlayerActions.set(player,new OrgFakePlayerActions.Action("trade",List.of(),0,"",null,false,false,true,net.minecraft.world.phys.Vec3.ZERO,net.minecraft.world.phys.Vec3.ZERO));
        }
        CompletableFuture<Void> tick(){return ScarpetNativeWork.observeNative(player,()->{OrgFakePlayerActions.tick(player);return null;});}
        @SuppressWarnings("unchecked") <T> CompletableFuture<T> step(String name,Class<?>[] types,Object... values) {
            try {
                var statesField=OrgFakePlayerActions.class.getDeclaredField("STATES");statesField.setAccessible(true);Object state=((Map<?,?>)statesField.get(null)).get(player);
                Class<?> type=Arrays.stream(OrgFakePlayerActions.class.getDeclaredClasses()).filter(candidate->candidate.getSimpleName().equals("MenuSteps")).findFirst().orElseThrow();
                var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);Object steps=constructor.newInstance(player,state,menu);
                var method=type.getDeclaredMethod(name,types);method.setAccessible(true);return (CompletableFuture<T>)method.invoke(steps,values);
            } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
        void librarian(net.minecraft.world.entity.npc.villager.Villager villager,MerchantOffer offer) {
            try {
                var statesField=OrgFakePlayerActions.class.getDeclaredField("STATES");statesField.setAccessible(true);Object state=((Map<?,?>)statesField.get(null)).get(player);
                var method=OrgFakePlayerActions.class.getDeclaredMethod("librarianLock",ServerBot.class,state.getClass(),net.minecraft.world.entity.npc.villager.Villager.class,MerchantOffer.class,int.class);method.setAccessible(true);
                try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)) { OrgGameplayHelper.withOrgAction(()->{try{method.invoke(null,player,state,villager,offer,4);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}); }
            } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
        public void close(){global.close();actors.close();}
    }
    @Test void actualTickWaitsEachOutputThenCloseAndFinalSyncAndBlocksSnapshotUntilWholeAction() throws Exception {
        try(var f=new TradeFixture()) {
            var parent=f.tick();assertEquals(List.of("click 1"),f.order);assertFalse(parent.isDone());assertFalse(OrgGameplayHelper.insideOrgAction());
            var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(idle.isDone());
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(f.player,()->"after menu");assertFalse(snapshot.isDone());
            f.actors.owner.set(null);f.first.complete(null);assertEquals(List.of("click 1"),f.order);f.actors.drain(f.bot);
            assertEquals(List.of("click 1","click 2"),f.order);assertFalse(parent.isDone());assertFalse(snapshot.isDone());
            f.last.complete(null);f.actors.drain(f.bot);assertEquals(List.of("click 1","click 2","close","broadcast","equipment"),f.order);
            parent.get(3,TimeUnit.SECONDS);idle.get(3,TimeUnit.SECONDS);assertEquals("after menu",snapshot.get(3,TimeUnit.SECONDS));
        }
    }
    @Test void actualFirstClickNativeFailureSuppressesRemainingOutputsCloseAndSync() throws Exception {
        try(var f=new TradeFixture()) {
            var parent=f.tick();f.first.completeExceptionally(new IllegalStateException("native trade failure"));f.actors.drain(f.bot);f.actors.drain(f.actors.viewer);f.actors.drain(f.actors.target);f.actors.drain(f.bot);
            assertEquals(List.of("click 1"),f.order);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class,()->parent.get(3,TimeUnit.SECONDS))));
            verify(f.player,never()).closeContainer();verify(f.menu,never()).broadcastChanges();verify(f.player,never()).detectEquipmentUpdates();
            f.actors.owner.set(f.player);assertEquals("stop",OrgFakePlayerActions.get(f.player).kind());
        }
    }
    @Test void actualGuestChildFailureRemainsInParentAndNativeActionStillUsesRealMenuValues() throws Exception {
        try(var f=new TradeFixture()) {
            var parent=f.tick();var failure=new IllegalArgumentException("guest trade callback");var marker=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);marker.setAccessible(true);marker.invoke(null,failure);
            f.first.completeExceptionally(failure);f.actors.drain(f.bot);assertEquals(List.of("click 1","click 2"),f.order);assertFalse(parent.isDone());
            f.last.complete(null);f.actors.drain(f.bot);assertEquals(List.of("click 1","click 2","close","broadcast","equipment"),f.order);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class,()->parent.get(3,TimeUnit.SECONDS))));
        }
    }
    @Test void actualCollectionWaitsPickupThenDropBeforeRecheckingOutputAndReturningProducedValue() throws Exception {
        try(var f=new TradeFixture()) {
            var cursor=new java.util.concurrent.atomic.AtomicReference<>(ItemStack.EMPTY);when(f.menu.getCarried()).thenAnswer(call->cursor.get());
            var container=new SimpleContainer(3);var diamond=new ItemStack(Items.DIAMOND);f.menu.slots.set(2,new Slot(container,2,0,0){@Override public ItemStack getItem(){return f.clicks.get()==0?diamond:ItemStack.EMPTY;}});
            doAnswer(call->{assertTrue(OrgGameplayHelper.insideOrgAction());f.clicks.incrementAndGet();cursor.set(diamond);f.order.add("pickup");ScarpetNativeWork.record(f.first);return null;}).when(f.menu).clicked(2,0,ContainerInput.PICKUP,f.player);
            doAnswer(call->{cursor.set(ItemStack.EMPTY);f.order.add("drop");ScarpetNativeWork.record(f.last);return null;}).when(f.menu).clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,0,ContainerInput.PICKUP,f.player);
            var value=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();var parent=ScarpetNativeWork.observeNative(f.player,()->{var actual=f.<Boolean>step("collect",new Class<?>[]{int.class},2);value.set(actual);ScarpetNativeWork.record(actual);return null;});
            assertEquals(List.of("pickup"),f.order);assertFalse(value.get().isDone());f.first.complete(null);assertEquals(List.of("pickup","drop"),f.order);assertFalse(parent.isDone());assertFalse(value.get().isDone());
            f.last.complete(null);assertTrue(value.get().get(3,TimeUnit.SECONDS));parent.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void actualReturnSlotWaitsPickupBeforeInventoryInsertionAndFinalClick() throws Exception {
        try(var f=new TradeFixture()) {
            var cursor=new java.util.concurrent.atomic.AtomicReference<>(ItemStack.EMPTY);when(f.menu.getCarried()).thenAnswer(call->cursor.get());doAnswer(call->{cursor.set(call.getArgument(0));return null;}).when(f.menu).setCarried(any(ItemStack.class));
            var container=new SimpleContainer(3);var emerald=new ItemStack(Items.EMERALD,3);container.setItem(0,emerald);f.menu.slots.set(0,new Slot(container,0,0,0));
            doAnswer(call->{if(f.clicks.incrementAndGet()==1){container.setItem(0,ItemStack.EMPTY);cursor.set(emerald);f.order.add("pickup");ScarpetNativeWork.record(f.first);}else{f.order.add("final click");ScarpetNativeWork.record(f.last);}return null;}).when(f.menu).clicked(0,0,ContainerInput.PICKUP,f.player);
            var parent=ScarpetNativeWork.observeNative(f.player,()->{var actual=f.<Void>step("returnSlot",new Class<?>[]{int.class},0);ScarpetNativeWork.record(actual);return actual;});
            assertEquals(List.of("pickup"),f.order);assertFalse(f.player.getInventory().getItem(0).is(Items.EMERALD));f.first.complete(null);
            assertEquals(List.of("pickup","final click"),f.order);assertTrue(f.player.getInventory().getItem(0).is(Items.EMERALD));assertEquals(3,f.player.getInventory().getItem(0).getCount());assertTrue(cursor.get().isEmpty());assertFalse(parent.isDone());
            f.last.complete(null);parent.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void actualLibrarianInteractionWaitsDynamicOpeningChildrenBeforeReadingMenuAndClosing() throws Exception {
        try(var f=new TradeFixture()) {
            var villager=mock(net.minecraft.world.entity.npc.villager.Villager.class);var offer=mock(MerchantOffer.class);when(offer.getBaseCostA()).thenReturn(new ItemStack(Items.EMERALD,5));
            var opened=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();var cursor=new java.util.concurrent.atomic.AtomicReference<>(ItemStack.EMPTY);when(f.menu.getCarried()).thenAnswer(call->cursor.get());
            when(villager.mobInteract(f.player,net.minecraft.world.InteractionHand.MAIN_HAND)).thenAnswer(call->{assertTrue(OrgGameplayHelper.insideOrgAction());f.order.add("interact");ScarpetNativeWork.record(opened);ScarpetNativeWork.record(late);return net.minecraft.world.InteractionResult.SUCCESS;});
            doAnswer(call->{cursor.set(new ItemStack(Items.DIAMOND));f.order.add("pickup");ScarpetNativeWork.record(f.first);return null;}).when(f.menu).clicked(2,0,ContainerInput.PICKUP,f.player);
            doAnswer(call->{cursor.set(ItemStack.EMPTY);f.order.add("drop");ScarpetNativeWork.record(f.last);return null;}).when(f.menu).clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,0,ContainerInput.PICKUP,f.player);
            clearInvocations(f.menu);var parent=ScarpetNativeWork.observeNative(f.player,()->{f.librarian(villager,offer);return null;});assertEquals(List.of("interact"),f.order);verify(f.menu,never()).getOffers();
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(f.player,()->"after librarian");assertFalse(snapshot.isDone());opened.complete(null);assertEquals(List.of("interact"),f.order);verify(f.menu,never()).getOffers();
            f.actors.owner.set(null);late.complete(null);f.actors.drain(f.bot);assertEquals(List.of("interact","pickup"),f.order);f.first.complete(null);f.actors.drain(f.bot);assertEquals(List.of("interact","pickup","drop"),f.order);assertFalse(parent.isDone());
            f.last.complete(null);f.actors.drain(f.bot);assertEquals(List.of("interact","pickup","drop","close"),f.order);assertFalse(parent.isDone());
            f.actors.drain(f.actors.viewer);f.actors.drain(f.actors.target);f.actors.drain(f.bot);parent.get(3,TimeUnit.SECONDS);assertEquals("after librarian",snapshot.get(3,TimeUnit.SECONDS));
            verify(f.actors.viewer.player()).sendSystemMessage(argThat(message->message.getString().contains("level 4, price 5")&&message.getString().contains("trade locked")));
        }
    }
    @Test void actualLibrarianOpeningNativeFailureNeverReadsOffersOrClosesContainer() throws Exception {
        try(var f=new TradeFixture()) {
            var villager=mock(net.minecraft.world.entity.npc.villager.Villager.class);var opening=new CompletableFuture<Void>();when(villager.mobInteract(f.player,net.minecraft.world.InteractionHand.MAIN_HAND)).thenAnswer(call->{ScarpetNativeWork.record(opening);return net.minecraft.world.InteractionResult.SUCCESS;});
            clearInvocations(f.menu);var parent=ScarpetNativeWork.observeNative(f.player,()->{f.librarian(villager,mock(MerchantOffer.class));return null;});opening.completeExceptionally(new IllegalStateException("real merchant opening failed"));
            assertThrows(ExecutionException.class,()->parent.get(3,TimeUnit.SECONDS));verify(f.menu,never()).getOffers();verify(f.player,never()).closeContainer();
        }
    }
    private void singleOutputPerRound(TradeFixture fixture) {
        var visible=new java.util.concurrent.atomic.AtomicBoolean();var diamond=new ItemStack(Items.DIAMOND);
        fixture.menu.slots.set(2,new Slot(new SimpleContainer(3),2,0,0){@Override public ItemStack getItem(){return visible.get()?diamond:ItemStack.EMPTY;}});
        doAnswer(call->{visible.set(true);return null;}).when(fixture.menu).setSelectionHint(0);
        doAnswer(call->{visible.set(false);int number=fixture.clicks.incrementAndGet();fixture.order.add("click "+number);ScarpetNativeWork.record(number==1?fixture.first:fixture.last);return null;}).when(fixture.menu).clicked(2,0,ContainerInput.THROW,fixture.player);
    }
    @Test void actualVoidTradeWithoutInfiniteTradesDoesNotUseTheInfiniteTradeCountLimit()throws Exception {
        int maximum=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount;boolean infinite=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade;
        try(var f=new TradeFixture()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=1;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=false;singleOutputPerRound(f);
            var actual=f.<Void>step("trade",new Class<?>[0]);assertEquals(1,f.clicks.get());f.first.complete(null);assertEquals(2,f.clicks.get());assertFalse(actual.isDone());f.last.complete(null);actual.get(3,TimeUnit.SECONDS);verify(f.player).closeContainer();
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=maximum;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=infinite;}
    }
    @Test void actualInfiniteTradeCountLimitStopsAfterTheFirstCompletedRound()throws Exception {
        int maximum=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount;boolean infinite=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade;
        try(var f=new TradeFixture()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=1;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=true;singleOutputPerRound(f);
            var actual=f.<Void>step("trade",new Class<?>[0]);assertEquals(1,f.clicks.get());assertFalse(actual.isDone());f.first.complete(null);actual.get(3,TimeUnit.SECONDS);assertEquals(1,f.clicks.get());verify(f.player).closeContainer();
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=maximum;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=infinite;}
    }
    @Test void actualEndlessVoidTradeThrowsBeforeTheSource1001stRoundAndDoesNotClose()throws Exception {
        int maximum=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount;boolean infinite=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade;
        try(var f=new TradeFixture()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=0;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=false;singleOutputPerRound(f);f.first.complete(null);f.last.complete(null);var offer=f.menu.getOffers().getFirst();doReturn(false).when(offer).isOutOfStock();
            var actual=f.<Void>step("trade",new Class<?>[0]);var failure=assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS));assertInstanceOf(OrgActionInfiniteLoopException.class,failure.getCause());assertEquals("Maximum loop count exceeded, possible infinite loop detected",failure.getCause().getMessage());assertEquals(1000,f.clicks.get());verify(f.player,never()).closeContainer();
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerMaxItemOperationCount=maximum;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.villagerInfiniteTrade=infinite;}
    }

}
