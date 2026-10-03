package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.CarpetEventServer;
import carpet.script.external.ScarpetInteractionContinuations;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ScarpetNativeInteractionTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        try { Items.STONE.builtInRegistryHolder().components(); }
        catch(NullPointerException unbound) { Items.STONE.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,64).build()); }
    }
    private static class Placement extends BlockItem {
        int writes;
        java.util.concurrent.atomic.AtomicReference<BlockState> state;
        Placement() { super(Blocks.STONE,new Item.Properties()); }
        @Override public Block getBlock() { return Blocks.STONE; }
        @Override protected BlockState getPlacementState(BlockPlaceContext context) { return Blocks.STONE.defaultBlockState(); }
        @Override protected boolean placeBlock(BlockPlaceContext context,BlockState placement) { writes++; state.set(placement); return true; }
    }
    private static final class Owner implements AutoCloseable {
        final ServerPlayer player=mock(ServerPlayer.class);
        final ServerLevel world=mock(ServerLevel.class);
        final MinecraftServer server=mock(MinecraftServer.class);
        final ItemStack held=new ItemStack(Items.STONE,8);
        final io.papermc.paper.threadedregions.EntityScheduler scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        final ArrayDeque<Consumer<Entity>> tasks=new ArrayDeque<>();
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        Owner() throws Exception {
            var bukkit=mock(CraftPlayer.class); when(player.getBukkitEntity()).thenReturn(bukkit);
            var field=CraftEntity.class.getField("taskScheduler"); field.setAccessible(true); field.set(bukkit,scheduler);
            when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
            when(player.carpetSpawnServer()).thenReturn(server); when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            when(server.reloadableRegistries()).thenReturn(new net.minecraft.server.ReloadableServerRegistries.Holder(
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)));
            when(world.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(1));
            when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
            when(player.getAdvancements()).thenReturn(mock(PlayerAdvancements.class));
            when(player.isWithinBlockInteractionRange(any(BlockPos.class),anyDouble())).thenReturn(true);
            when(player.isWithinEntityInteractionRange(any(Entity.class),anyDouble())).thenReturn(true);
            when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
            player.containerMenu=mock(AbstractContainerMenu.class);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(world,BlockPos.ZERO)).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(world,0,0,8)).thenReturn(true);
            when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call -> { tasks.add(call.getArgument(0)); return true; });
        }
        void tick() { Consumer<Entity> task=tasks.removeFirst(); task.accept(player); }
        @Override public void close() { ticks.close(); }
    }

    @Test void nativePlacementResumesItsActualWriteAndResultTailOnceAndCancellationSkipsTheWrite() throws Exception {
        for(boolean cancelled:new boolean[]{false,true}) try(Owner owner=new Owner();
            var states=mockStatic(org.bukkit.craftbukkit.block.CraftBlockStates.class)) {
            Placement item=mock(Placement.class,CALLS_REAL_METHODS);
            item.state=new java.util.concurrent.atomic.AtomicReference<>(Blocks.AIR.defaultBlockState());
            when(owner.world.getBlockState(BlockPos.ZERO)).thenAnswer(call -> item.state.get());
            states.when(()->org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(owner.world,BlockPos.ZERO)).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlockState.class));
            BlockPlaceContext context=mock(BlockPlaceContext.class);
            when(context.canPlace()).thenReturn(true); when(context.getPlayer()).thenReturn(owner.player);
            when(context.getLevel()).thenReturn(owner.world); when(context.getClickedPos()).thenReturn(BlockPos.ZERO);
            when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND); when(context.getItemInHand()).thenReturn(owner.held);
            var event=CarpetEventServer.Event.PLAYER_PLACING_BLOCK;
            var original=event.handler;
            var replacement=mock(CarpetEventServer.CallbackList.class);
            var calls=CarpetEventServer.CallbackList.class.getDeclaredField("callList"); calls.setAccessible(true);
            calls.set(replacement,List.of(mock(CarpetEventServer.Callback.class)));
            when(replacement.call(any(),any())).thenReturn(cancelled);
            var handler=CarpetEventServer.Event.class.getField("handler"); handler.setAccessible(true); handler.set(event,replacement);
            var order=new ArrayList<String>();
            InteractionResult.Deferred pending;
            try {
                try(var outer=ScarpetInteractionContinuations.open()) {
                    pending=assertInstanceOf(InteractionResult.Deferred.class,item.place(context));
                    pending.plan().around(next -> { order.add("capture begin"); var result=next.get(); order.add("capture end"); return result; });
                    pending.plan().map(result -> { order.add("game mode tail"); return result; });
                    pending.plan().onComplete(result -> order.add("packet tail"));
                    assertEquals(0,item.writes); assertEquals(8,owner.held.getCount()); assertTrue(owner.tasks.isEmpty());
                }
                owner.tick();
                assertEquals(!cancelled,pending.plan().future().get(2,TimeUnit.SECONDS).consumesAction());
                assertEquals(cancelled ? 0 : 1,item.writes);
                assertEquals(cancelled ? 8 : 7,owner.held.getCount());
                assertEquals(List.of("capture begin","capture end","game mode tail","packet tail"),order);
                verify(item,times(1)).getPlacementState(context);
                verify(replacement,times(1)).call(any(),any());
                assertTrue(owner.tasks.isEmpty());
            } finally { handler.set(event,original); }
        }
    }

    @Test void nativeEntityInteractionWaitsForTheSharedItemBeforeApplyingAnyWorldEffect() throws Exception {
        try(Owner owner=new Owner(); var executor=Executors.newSingleThreadExecutor()) {
            OrgItemShadowGroups.share(owner.held);
            CountDownLatch borrowed=new CountDownLatch(1), release=new CountDownLatch(1);
            var holder=executor.submit(()->OrgItemShadowGroups.attempt(List.of(owner.held),()-> {
                borrowed.countDown(); try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException failure){throw new AssertionError(failure);} return true;
            }));
            assertTrue(borrowed.await(2,TimeUnit.SECONDS));
            Entity target=mock(Entity.class); when(target.level()).thenReturn(owner.world);
            var effects=new java.util.concurrent.atomic.AtomicInteger();
            when(target.interact(owner.player,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenAnswer(call -> { effects.incrementAndGet(); owner.held.shrink(3); return InteractionResult.SUCCESS; });
            doCallRealMethod().when(owner.player).interactOn(target,InteractionHand.MAIN_HAND,Vec3.ZERO);
            try {
                var pending=assertInstanceOf(InteractionResult.Deferred.class,owner.player.interactOn(target,InteractionHand.MAIN_HAND,Vec3.ZERO));
                assertEquals(0,effects.get()); owner.tick(); assertEquals(0,effects.get()); assertFalse(pending.plan().future().isDone());
                release.countDown(); holder.get(2,TimeUnit.SECONDS); owner.tick();
                assertTrue(pending.plan().future().get(2,TimeUnit.SECONDS).consumesAction());
                assertEquals(1,effects.get()); assertEquals(5,owner.held.getCount()); assertTrue(owner.tasks.isEmpty());
            } finally { release.countDown(); }
        }
    }
}
