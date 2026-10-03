package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class CarpetHopperCounterSourceTest {
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();try{Items.STONE.builtInRegistryHolder().components();}catch(NullPointerException unbound){Items.STONE.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64).build());}}
    static final class Fixture implements AutoCloseable {
        final ServerLevel world=mock(ServerLevel.class);
        final MinecraftServer server=mock(MinecraftServer.class);
        final BlockState state=Blocks.HOPPER.defaultBlockState();
        final HopperBlockEntity hopper=new HopperBlockEntity(BlockPos.ZERO,state);
        final org.leavesmc.leaves.util.HopperCounter counter=mock(org.leavesmc.leaves.util.HopperCounter.class);
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<org.leavesmc.leaves.util.HopperCounter> counters=mockStatic(org.leavesmc.leaves.util.HopperCounter.class);
        final org.mockito.MockedStatic<org.leavesmc.leaves.util.WoolUtils> wool=mockStatic(org.leavesmc.leaves.util.WoolUtils.class);
        final boolean oldRule=WoolHopperCounterConfig.hopperCountersUnlimitedSpeed;
        final boolean oldXp=GeneralCompatConfig.hopperXpCounters;
        final boolean oldSleeping=me.earthme.luminol.config.modules.optimizations.LeavesSleepingBlockEntityConfig.enabled;
        Fixture() throws Exception {
            WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=true;GeneralCompatConfig.hopperXpCounters=false;me.earthme.luminol.config.modules.optimizations.LeavesSleepingBlockEntityConfig.enabled=false;
            when(world.getServer()).thenReturn(server);when(world.hasChunkAt(any(BlockPos.class))).thenReturn(true);when(world.getBlockEntity(BlockPos.ZERO)).thenReturn(hopper);when(world.getBlockState(any(BlockPos.class))).thenReturn(state);hopper.setLevel(world);
            var config=mock(org.spigotmc.SpigotWorldConfig.class);config.hopperTransfer=8;config.hopperCheck=1;var field=Level.class.getField("spigotConfig");field.setAccessible(true);field.set(world,config);
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenReturn(true);
            counters.when(org.leavesmc.leaves.util.HopperCounter::isEnabled).thenReturn(true);counters.when(()->org.leavesmc.leaves.util.HopperCounter.getCounter(DyeColor.RED)).thenReturn(counter);
            wool.when(()->org.leavesmc.leaves.util.WoolUtils.getWoolColorAtPosition(eq(world),any(BlockPos.class))).thenReturn(DyeColor.RED);
        }
        boolean move(BooleanSupplier pull) {
            try {var method=HopperBlockEntity.class.getDeclaredMethod("tryMoveItems",Level.class,BlockPos.class,BlockState.class,HopperBlockEntity.class,BooleanSupplier.class);method.setAccessible(true);return (Boolean)method.invoke(null,world,BlockPos.ZERO,state,hopper,pull);}
            catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
        int cooldown() throws Exception {var field=HopperBlockEntity.class.getDeclaredField("cooldownTime");field.setAccessible(true);return field.getInt(hopper);}
        public void close(){wool.close();counters.close();ticks.close();WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=oldRule;GeneralCompatConfig.hopperXpCounters=oldXp;me.earthme.luminol.config.modules.optimizations.LeavesSleepingBlockEntityConfig.enabled=oldSleeping;}
    }
    @Test void actualHopperUsesFreshPassResultAndEjectsBeforePullAfterOriginalCooldown() throws Exception {
        try(var f=new Fixture()) {
            var count=new AtomicInteger();var trace=new ArrayList<Integer>();
            boolean moved=f.move(()->{trace.add(uncheckedCooldown(f));if(count.incrementAndGet()==1){f.hopper.setItem(0,new ItemStack(Items.STONE,3));return true;}return false;});
            assertTrue(moved);assertEquals(3,count.get());assertEquals(List.of(-1,8,8),trace);assertTrue(f.hopper.isEmpty());assertEquals(0,f.cooldown());verify(f.counter).add(eq(f.server),argThat(stack->stack.is(Items.STONE)&&stack.getCount()==3));
        }
    }
    private static int uncheckedCooldown(Fixture fixture){try{return fixture.cooldown();}catch(Exception failure){throw new AssertionError(failure);}}
    @Test void actualInitialAndEjectionChildrenHoldNextPassCooldownAndNativeCompletion() throws Exception {
        try(var f=new Fixture()) {
            var initial=new CompletableFuture<Void>();var ejected=new CompletableFuture<Void>();var calls=new AtomicInteger();
            doAnswer(call->{ScarpetNativeWork.record(ejected);return null;}).when(f.counter).add(any(),any());
            var parent=ScarpetNativeWork.observeNative(null,()->f.move(()->{if(calls.incrementAndGet()==1){f.hopper.setItem(0,new ItemStack(Items.STONE));ScarpetNativeWork.record(initial);return true;}return false;}));
            assertEquals(1,calls.get());assertEquals(-1,f.cooldown());assertFalse(parent.isDone());assertTrue(CarpetHopperCounters.pending(f.hopper));
            assertFalse(f.move(()->{fail("A second producer must wait for the accepted pass");return true;}));
            initial.complete(null);assertEquals(8,f.cooldown());assertEquals(1,calls.get());assertFalse(parent.isDone());
            ejected.complete(null);assertEquals(3,calls.get());assertEquals(0,f.cooldown());parent.get(3,TimeUnit.SECONDS);assertFalse(CarpetHopperCounters.pending(f.hopper));
        }
    }
    @Test void actualNativePullFailureBlocksCooldownEjectionAndRemainingPulls() throws Exception {
        try(var f=new Fixture()) {
            var child=new CompletableFuture<Void>();var calls=new AtomicInteger();var parent=ScarpetNativeWork.observeNative(null,()->f.move(()->{calls.incrementAndGet();ScarpetNativeWork.record(child);return true;}));
            child.completeExceptionally(new IllegalStateException("real pull child failed"));assertThrows(ExecutionException.class,()->parent.get(3,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(-1,f.cooldown());verifyNoInteractions(f.counter);assertFalse(CarpetHopperCounters.pending(f.hopper));
        }
    }
    @Test void actualRemovedItemGuardRunsEvenWhenCounterIsDisabledBeforeItemOrInventoryReads() {
        boolean old=WoolHopperCounterConfig.hopperCountersUnlimitedSpeed;WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=true;
        try(var counters=mockStatic(org.leavesmc.leaves.util.HopperCounter.class)) {
            var item=mock(ItemEntity.class);when(item.isRemoved()).thenReturn(true);var inventory=mock(SimpleContainer.class);
            assertFalse(HopperBlockEntity.addItem(inventory,item));verify(item,never()).getItem();verifyNoInteractions(inventory);counters.verifyNoInteractions();
        } finally {WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=old;}
    }
    @Test void actualCooldownTickDoesNotRunPostTransferCheckUntilTheOriginalBranchOpens() throws Exception {
        try(var f=new Fixture()) {
            var field=HopperBlockEntity.class.getDeclaredField("cooldownTime");field.setAccessible(true);field.setInt(f.hopper,5);f.world.spigotConfig.hopperCheck=7;
            HopperBlockEntity.pushItemsTick(f.world,BlockPos.ZERO,f.state,f.hopper);assertEquals(4,f.cooldown());verifyNoInteractions(f.counter);assertFalse(CarpetHopperCounters.pending(f.hopper));
        }
    }
    @Test void actualGuestPullFailureKeepsRawParentFailureAndContinuesOriginalPhysicalPasses() throws Exception {
        try(var f=new Fixture()) {
            var child=new CompletableFuture<Void>();var calls=new AtomicInteger();var parent=ScarpetNativeWork.observeNative(null,()->f.move(()->{
                if(calls.incrementAndGet()!=1)return false;f.hopper.setItem(0,new ItemStack(Items.STONE));ScarpetNativeWork.record(child);return true;
            }));
            var failure=new IllegalArgumentException("guest pull callback");var marker=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);marker.setAccessible(true);marker.invoke(null,failure);child.completeExceptionally(failure);
            assertEquals(3,calls.get());assertEquals(0,f.cooldown());assertTrue(f.hopper.isEmpty());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class,()->parent.get(3,TimeUnit.SECONDS))));assertFalse(CarpetHopperCounters.pending(f.hopper));
        }
    }
    @Test void actualLateRuleEnableDuringTheOriginalPullEntersTheDrainHook() throws Exception {
        try(var f=new Fixture()) {
            WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=false;
            var child=new CompletableFuture<Void>();var calls=new AtomicInteger();
            var parent=ScarpetNativeWork.observeNative(null,()->f.move(()->{
                if(calls.incrementAndGet()!=1)return false;
                f.hopper.setItem(0,new ItemStack(Items.STONE,2));ScarpetNativeWork.record(child);return true;
            }));
            assertEquals(1,calls.get());assertEquals(-1,f.cooldown());assertFalse(parent.isDone());
            WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=true;child.complete(null);
            parent.get(3,TimeUnit.SECONDS);assertEquals(3,calls.get());assertEquals(0,f.cooldown());assertTrue(f.hopper.isEmpty());verify(f.counter).add(eq(f.server),argThat(stack->stack.getCount()==2));
        }
    }
    @Test void actualDisabledRuleKeepsTheOriginalSinglePassAndTransferCooldown() throws Exception {
        try(var f=new Fixture()) {
            WoolHopperCounterConfig.hopperCountersUnlimitedSpeed=false;var calls=new AtomicInteger();
            assertTrue(f.move(()->{calls.incrementAndGet();f.hopper.setItem(0,new ItemStack(Items.STONE,2));return true;}));
            assertEquals(1,calls.get());assertEquals(8,f.cooldown());assertEquals(2,f.hopper.getItem(0).getCount());verifyNoInteractions(f.counter);
        }
    }

}
