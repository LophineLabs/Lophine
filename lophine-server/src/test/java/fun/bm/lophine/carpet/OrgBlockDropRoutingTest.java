package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class OrgBlockDropRoutingTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    private static void guest(Throwable failure) throws Exception {
        var mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
        mark.setAccessible(true); mark.invoke(null, failure);
    }
    private static void fill(OrgInventoryPersistenceTest.Actor actor, int available) {
        actor.player().inventoryMenu.slots=net.minecraft.core.NonNullList.create();
        for (int index=0; index<actor.inventory().getContainerSize(); index++) actor.inventory().setItem(index, new ItemStack(Items.EMERALD,64));
        actor.inventory().setItem(0,new ItemStack(Items.DIAMOND,64-available));
    }
    private static ItemEntity item(ItemStack stack) {
        var item=mock(ItemEntity.class); when(item.getItem()).thenReturn(stack);when(item.blockPosition()).thenReturn(new BlockPos(27,70,-20)); return item;
    }
    private static final class WorldQueue implements AutoCloseable {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>(); final MockedStatic<CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        boolean owned; final ServerLevel world;
        WorldQueue(ServerLevel world) {
            this.world=world;
            leases.when(()->CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                var actual=new CompletableFuture<Object>();Function<CarpetRegionLease.Lease<Object>,Object> body=call.getArgument(5);
                tasks.add(()->{owned=true;try{actual.complete(body.apply(null));}catch(Throwable failure){actual.completeExceptionally(failure);}finally{owned=false;}});return actual;
            });
        }
        void drain(){while(!tasks.isEmpty()) tasks.removeFirst().run();}
        public void close(){leases.close();}
    }
    @Test void exactStackRealInventoryPartialAddAndOriginalWorldRemnantConsumeTheTrueDeferredBoolean() throws Exception {
        String old=GeneralCompatConfig.blockDropsDirectlyEnterInventory;GeneralCompatConfig.blockDropsDirectlyEnterInventory="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            fill(fixture.target,4);var stack=new ItemStack(Items.DIAMOND,10);var item=item(stack);var addChild=new CompletableFuture<Void>();var seen=new AtomicInteger();var actual=new AtomicReference<CompletableFuture<Boolean>>();
            var parent=ScarpetNativeWork.observeNative(null,()->{
                actual.set(OrgBlockDropRouting.addNative(source.world,item,()->{
                    var routed=OrgBlockDropRouting.routeNative(source.world,item,fixture.target.player(),()->{
                        assertTrue(source.owned);assertSame(stack,item.getItem());assertEquals(6,stack.getCount());seen.incrementAndGet();ScarpetNativeWork.record(addChild);return false;
                    });
                    return routed.isDone() && routed.getNow(false);
                }));return null;
            });
            assertFalse(actual.get().isDone());assertEquals(10,stack.getCount());assertEquals(0,seen.get());
            var cancelled=OrgBlockDropRouting.pendingResult(item);assertTrue(cancelled.cancel(false));
            fixture.drain(fixture.target);assertEquals(64,fixture.target.inventory().getItem(0).getCount());assertEquals(6,stack.getCount());assertEquals(0,seen.get());
            source.drain();assertEquals(1,seen.get());assertFalse(actual.get().isDone());assertFalse(parent.isDone());assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            addChild.complete(null);assertFalse(actual.get().join());parent.join();ScarpetNativeWork.whenIdle(fixture.server).join();
            verify(item,never()).discard();
        } finally {GeneralCompatConfig.blockDropsDirectlyEnterInventory=old;}
    }
    @Test void fullyCollectedDropWaitsItsRealRemovalChildrenAndGuestOnlyFailureKeepsTheParentFailed() throws Exception {
        String old=GeneralCompatConfig.blockDropsDirectlyEnterInventory;GeneralCompatConfig.blockDropsDirectlyEnterInventory="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            fill(fixture.target,4);var stack=new ItemStack(Items.DIAMOND,4);var item=item(stack);var guestChild=new CompletableFuture<Void>();var nativeChild=new CompletableFuture<Void>();var actual=new AtomicReference<CompletableFuture<Boolean>>();var spawned=new AtomicInteger();
            doAnswer(call->{assertTrue(source.owned);ScarpetNativeWork.record(guestChild);ScarpetNativeWork.record(nativeChild);return null;}).when(item).discard();
            var parent=ScarpetNativeWork.observeNative(null,()->{actual.set(OrgBlockDropRouting.routeNative(source.world,item,fixture.target.player(),()->{spawned.incrementAndGet();return true;}));return null;});
            fixture.drain(fixture.target);source.drain();assertTrue(stack.isEmpty());assertFalse(actual.get().isDone());
            var failure=new IllegalArgumentException("actual Guest removal callback");guest(failure);guestChild.completeExceptionally(failure);assertFalse(actual.get().isDone());assertFalse(parent.isDone());
            nativeChild.complete(null);assertTrue(actual.get().join());assertEquals(0,spawned.get());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));
        } finally {GeneralCompatConfig.blockDropsDirectlyEnterInventory=old;}
    }
    @Test void nativeFailureFromThePhysicalInventoryOperationBlocksAllOriginalWorldTailWork() throws Exception {
        String old=GeneralCompatConfig.blockDropsDirectlyEnterInventory;GeneralCompatConfig.blockDropsDirectlyEnterInventory="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            var player=fixture.target.player();var failing=mock(net.minecraft.world.entity.player.Inventory.class);when(player.getInventory()).thenReturn(failing);when(failing.getContainerSize()).thenReturn(0);
            player.inventoryMenu.slots=net.minecraft.core.NonNullList.create();
            when(failing.add(any(ItemStack.class))).thenThrow(new IllegalStateException("real native inventory add failure"));
            var stack=new ItemStack(Items.DIAMOND,4);var actual=new AtomicReference<CompletableFuture<Boolean>>();var spawned=new AtomicInteger();
            var parent=ScarpetNativeWork.observeNative(null,()->{actual.set(OrgBlockDropRouting.routeNative(source.world,item(stack),player,()->{spawned.incrementAndGet();return true;}));return null;});
            fixture.drain(fixture.target);source.drain();verify(failing).add(stack);assertEquals(0,spawned.get());assertEquals(4,stack.getCount());assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual.get()::join)));assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));
        } finally {GeneralCompatConfig.blockDropsDirectlyEnterInventory=old;}
    }
    @Test void creativeSuppressionIsReadOnTheActualBreakerAndOriginalWorldScatterRetainsStackIdentity() throws Exception {
        boolean old=GeneralCompatConfig.disableCreativeContainerDrops;GeneralCompatConfig.disableCreativeContainerDrops=true;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            var breaker=fixture.target.player();var stack=new ItemStack(Items.DIAMOND,9);var seen=new AtomicInteger();
            doAnswer(call->{assertSame(breaker,fixture.owner.get());return true;}).when(breaker).isCreative();
            var skip=OrgBlockDropRouting.scatterNative(source.world,new BlockPos(20,70,-20),stack,breaker,()->{seen.incrementAndGet();return null;});
            assertFalse(skip.isDone());fixture.drain(fixture.target);skip.join();assertEquals(0,seen.get());assertEquals(9,stack.getCount());
            fixture.owner.set(null);doAnswer(call->{assertSame(breaker,fixture.owner.get());return false;}).when(breaker).isCreative();
            var physical=OrgBlockDropRouting.scatterNative(source.world,new BlockPos(20,70,-20),stack,breaker,()->{assertTrue(source.owned);assertEquals(9,stack.getCount());stack.shrink(3);seen.incrementAndGet();return null;});
            fixture.drain(fixture.target);assertFalse(physical.isDone());assertEquals(9,stack.getCount());source.drain();physical.join();assertEquals(6,stack.getCount());assertEquals(1,seen.get());
        } finally {GeneralCompatConfig.disableCreativeContainerDrops=old;}
    }
    @Test void truePassengerAddResultCannotPublishOrStartItsPassengerBeforeTheRootActualAddCompletes() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            var root=mock(Entity.class);var passenger=mock(Entity.class);when(root.level()).thenReturn(source.world);when(passenger.level()).thenReturn(source.world);when(root.blockPosition()).thenReturn(new BlockPos(27,70,-20));when(passenger.blockPosition()).thenReturn(new BlockPos(27,70,-20));
            var rootAdd=new CompletableFuture<Boolean>();var passengerAdd=new CompletableFuture<Boolean>();var sequence=new ArrayList<Entity>();
            when(source.world.carpetAddFreshEntityNativeAsync(any(),any())).thenAnswer(call->{Entity entity=call.getArgument(0);sequence.add(entity);return entity==root?rootAdd:passengerAdd;});
            var actual=OrgBlockDropRouting.addTreeNative(source.world,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT,()->List.of(root,passenger));
            assertFalse(actual.isDone());source.drain();assertEquals(List.of(root),sequence);assertFalse(actual.isDone());rootAdd.complete(true);assertEquals(List.of(root),sequence);source.drain();assertEquals(List.of(root,passenger),sequence);assertFalse(actual.isDone());passengerAdd.complete(false);assertFalse(actual.join());
        }
    }
    @Test void inventorySnapshotGateAdmitsTheDropOnlyAfterItsActualSnapshotEnds() throws Exception {
        String old=GeneralCompatConfig.blockDropsDirectlyEnterInventory;GeneralCompatConfig.blockDropsDirectlyEnterInventory="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var source=new WorldQueue(fixture.viewer.player().level())) {
            fill(fixture.target,4);var player=fixture.target.player();var world=player.level();when(world.registryAccess()).thenReturn(fixture.lookup);fixture.owner.set(player);
            var snapshotTail=new CompletableFuture<Void>();
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,()->snapshotTail.thenApply(ignored->fixture.target.inventory().getItem(0).getCount()))
                .thenCompose(java.util.function.Function.identity());
            assertFalse(snapshot.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(player));
            fixture.owner.set(null);var stack=new ItemStack(Items.DIAMOND,4);var actual=OrgBlockDropRouting.routeNative(source.world,item(stack),player,()->true);
            assertFalse(snapshot.isDone());assertFalse(actual.isDone());assertEquals(4,stack.getCount());
            fixture.owner.set(player);snapshotTail.complete(null);assertEquals(60,snapshot.join());fixture.drain(fixture.target);source.drain();
            assertTrue(actual.join());assertTrue(stack.isEmpty());assertEquals(64,fixture.target.inventory().getItem(0).getCount());
        } finally {GeneralCompatConfig.blockDropsDirectlyEnterInventory=old;}
    }

    @Test void realServerPlayerShoulderTailKeepsTheSlotAndSnapshotUntilTheActualSpawnCompletes() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var creation=mockStatic(net.minecraft.world.entity.EntityType.class)) {
            var player=fixture.target.player();var world=player.level();when(world.registryAccess()).thenReturn(fixture.lookup);fixture.owner.set(player);
            var serverField=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("server");serverField.setAccessible(true);serverField.set(player,fixture.server);
            var tag=new net.minecraft.nbt.CompoundTag();tag.putString("id","minecraft:parrot");var shoulder=new AtomicReference<>(tag);
            when(player.getShoulderEntityLeft()).thenAnswer(call->shoulder.get());doAnswer(call->{assertSame(player,fixture.owner.get());shoulder.set(call.getArgument(0));return null;}).when(player).setShoulderEntityLeft(any());
            var entity=mock(Entity.class);when(entity.level()).thenReturn(world);when(entity.blockPosition()).thenReturn(new BlockPos(0,1,0));
            creation.when(()->net.minecraft.world.entity.EntityType.create(any(net.minecraft.world.level.storage.ValueInput.class),eq(world),any(net.minecraft.world.entity.EntitySpawnRequest.class))).thenReturn(java.util.Optional.of(entity));
            var spawn=new CompletableFuture<Boolean>();when(world.carpetAddWithUUIDNativeAsync(eq(entity),any())).thenAnswer(call->{ScarpetNativeWork.record(spawn);return spawn;});
            doCallRealMethod().when(player).carpetReleaseShoulderNativeAsync(anyBoolean());
            var actual=player.carpetReleaseShoulderNativeAsync(true);if(actual.isCompletedExceptionally())actual.join();assertFalse(actual.isDone());assertSame(tag,shoulder.get());
            var repeat=player.carpetReleaseShoulderNativeAsync(true);assertFalse(repeat.isDone());creation.verify(()->net.minecraft.world.entity.EntityType.create(any(net.minecraft.world.level.storage.ValueInput.class),eq(world),any(net.minecraft.world.entity.EntitySpawnRequest.class)),times(1));
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,()->shoulder.get().isEmpty());assertFalse(snapshot.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(player));
            spawn.complete(true);assertSame(entity,actual.join());assertSame(entity,repeat.join());assertTrue(shoulder.get().isEmpty());
            assertTrue(snapshot.isDone());assertTrue(snapshot.join());assertFalse(ScarpetPlayerInventoryGate.paused(player));
        }
    }
}
