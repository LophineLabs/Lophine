package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.CarpetDragonBreathDispenseBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Executes actual Default/Dispenser and dragon behavior with separate native source/target owners.
 */
public class TisDragonBreathContinuationsTest {
    @BeforeAll
    static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
        try {
            Items.DRAGON_BREATH.builtInRegistryHolder().components();
        } catch (NullPointerException unbound) {
            Items.DRAGON_BREATH.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                    .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }

    private record Task(BlockPos position, Runnable body) {
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final CraftServer craft = mock(CraftServer.class, RETURNS_DEEP_STUBS);
        final BlockPos sourcePos = new BlockPos(5, 60, 5), targetPos = new BlockPos(320, 60, 480);
        final DispenserBlockEntity inventory = mock(DispenserBlockEntity.class);
        final BlockState state = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST);
        final BlockSource source = new BlockSource(world, sourcePos, state, inventory);
        final ItemStack stack = spy(new ItemStack(Items.DRAGON_BREATH, 2));
        final org.bukkit.inventory.ItemStack original = mock(org.bukkit.inventory.ItemStack.class);
        final Queue<Task> tasks = new ArrayDeque<>();
        final List<String> order = new ArrayList<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<CraftEventFactory> pre = mockStatic(CraftEventFactory.class);
        final MockedStatic<CraftBlock> blocks = mockStatic(CraftBlock.class);
        final MockedStatic<CraftItemStack> items = mockStatic(CraftItemStack.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
        final org.mockito.MockedConstruction<AreaEffectCloud> clouds;
        final boolean oldDragon = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispensersFireDragonBreath;
        final boolean oldNoCost = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispenserNoItemCost;
        BlockPos owner;
        CompletableFuture<Void> particleChild, spawnChild, eventChild;
        BlockDispenseEvent event;
        boolean spawnResult = true;

        Fixture() throws Exception {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispensersFireDragonBreath = true;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispenserNoItemCost = false;
            var serverField = MinecraftServer.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(server, craft);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            when(world.getServer()).thenReturn(server);
            when(world.getCraftServer()).thenReturn(craft);
            when(world.getBlockEntity(sourcePos)).thenReturn(inventory);
            when(world.getBlockState(sourcePos)).thenReturn(state);
            when(inventory.getLevel()).thenReturn(world);
            when(inventory.getBlockPos()).thenReturn(sourcePos);
            when(inventory.getItem(0)).thenReturn(stack);
            var craftWorld = mock(CraftWorld.class);
            when(world.getWorld()).thenReturn(craftWorld);
            var scheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
            when(craft.getRegionScheduler()).thenReturn(scheduler);
            doAnswer(call -> {
                int x = call.getArgument(2), z = call.getArgument(3);
                BlockPos pos = x == (sourcePos.getX() >> 4) && z == (sourcePos.getZ() >> 4) ? sourcePos : targetPos;
                tasks.add(new Task(pos, call.getArgument(4)));
                return null;
            }).when(scheduler).execute(any(), eq(craftWorld), anyInt(), anyInt(), any());
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenAnswer(call -> call.getArgument(0) == world && call.getArgument(1).equals(owner));
            when(original.clone()).thenReturn(original);
            items.when(() -> CraftItemStack.asBukkitCopy(any())).thenReturn(original);
            blocks.when(() -> CraftBlock.at(world, sourcePos)).thenReturn(mock(CraftBlock.class));
            pre.when(() -> CraftEventFactory.handleBlockPreDispenseEvent(world, sourcePos, stack, 0)).thenAnswer(call -> {
                assertEquals(sourcePos, owner);
                order.add("pre event");
                return true;
            });
            var pluginManager = craft.getPluginManager();
            doAnswer(call -> {
                assertEquals(sourcePos, owner);
                event = call.getArgument(0);
                event.setVelocity(new org.bukkit.util.Vector(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5));
                order.add("event");
                if (eventChild != null) ScarpetNativeWork.record(eventChild);
                return null;
            }).when(pluginManager).callEvent(any(BlockDispenseEvent.class));
            clouds = mockConstruction(AreaEffectCloud.class, (cloud, context) -> {
                assertEquals(sourcePos, owner);
                assertSame(world, context.arguments().get(0));
                order.add("construct");
                when(cloud.getRadius()).thenReturn(3F);
                when(cloud.getDuration()).thenReturn(600);
            });
            doAnswer(call -> {
                BlockPos position = call.getArgument(1);
                assertEquals(position, owner);
                int id = call.getArgument(0);
                if (position.equals(targetPos)) {
                    order.add("particle");
                    assertEquals(LevelEvent.PARTICLES_INSTANT_POTION_SPLASH, id);
                    if (particleChild != null) ScarpetNativeWork.record(particleChild);
                } else order.add(id == LevelEvent.SOUND_DISPENSER_DISPENSE ? "sound" : "animation");
                return null;
            }).when(world).levelEvent(anyInt(), any(BlockPos.class), anyInt());
            when(world.addFreshEntity(any())).thenAnswer(call -> {
                assertEquals(targetPos, owner);
                assertSame(clouds.constructed().getFirst(), call.getArgument(0));
                order.add("spawn");
                if (spawnChild != null) ScarpetNativeWork.record(spawnChild);
                return spawnResult;
            });
            doAnswer(call -> {
                assertEquals(sourcePos, owner);
                order.add("shrink");
                return call.callRealMethod();
            }).when(stack).shrink(1);
            doAnswer(call -> {
                assertEquals(sourcePos, owner);
                assertSame(stack, call.getArgument(1));
                order.add("write");
                return null;
            }).when(inventory).setItem(eq(0), any());
        }

        CompletableFuture<Void> fire() throws Exception {
            owner = sourcePos;
            try {
                return ScarpetNativeWork.observeNative(null, () -> {
                    try {
                        Method selected = DispenserBlock.class.getDeclaredMethod("carpetOrgDispenseSelected", BlockSource.class, int.class, ItemStack.class);
                        selected.setAccessible(true);
                        selected.invoke(Blocks.DISPENSER, source, 0, stack);
                    } catch (ReflectiveOperationException failure) {
                        throw new RuntimeException(failure);
                    }
                    return null;
                });
            } finally {
                owner = null;
            }
        }

        void drain() {
            Task task;
            while ((task = tasks.poll()) != null) {
                owner = task.position();
                try {
                    task.body().run();
                } finally {
                    owner = null;
                }
            }
        }

        @Override
        public void close() {
            clouds.close();
            bukkit.close();
            items.close();
            blocks.close();
            pre.close();
            ticks.close();
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispensersFireDragonBreath = oldDragon;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispenserNoItemCost = oldNoCost;
        }
    }

    @Test
    void realDispenserWaitsForeignCloudNativeChildBeforeActualSourceCostEffectsAndWrite() throws Exception {
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var parent = f.fire();
            assertFalse(parent.isDone());
            f.drain();
            assertEquals(List.of("pre event", "event", "construct", "particle", "spawn"), f.order);
            assertEquals(2, f.stack.getCount());
            verify(f.inventory, never()).setItem(anyInt(), any());
            var global = ScarpetNativeWork.whenIdle(f.server);
            assertFalse(global.isDone());
            f.spawnChild.complete(null);
            f.drain();
            parent.join();
            global.join();
            assertEquals(List.of("pre event", "event", "construct", "particle", "spawn", "shrink", "sound", "animation", "write"), f.order);
            assertEquals(1, f.stack.getCount());
        }
    }

    @Test
    void genuineParticleChildFailureStopsSpawnAndAllSourceTails() throws Exception {
        try (var f = new Fixture()) {
            f.particleChild = new CompletableFuture<>();
            var parent = f.fire();
            f.drain();
            assertEquals(List.of("pre event", "event", "construct", "particle"), f.order);
            f.particleChild.completeExceptionally(new IllegalStateException("native particle callback"));
            f.drain();
            assertThrows(CompletionException.class, parent::join);
            assertEquals(2, f.stack.getCount());
            verify(f.world, never()).addFreshEntity(any());
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    @Test
    void genuineSpawnChildFailureStopsCostSoundAnimationAndInventoryWrite() throws Exception {
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var parent = f.fire();
            f.drain();
            f.spawnChild.completeExceptionally(new IllegalStateException("native spawn callback"));
            f.drain();
            assertThrows(CompletionException.class, parent::join);
            assertEquals(2, f.stack.getCount());
            assertEquals("spawn", f.order.getLast());
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    @Test
    void replacingTheSourceDuringForeignSpawnCannotWriteOrConsumeItsOldInventory() throws Exception {
        changedSource(false);
    }

    @Test
    void replacingTheSelectedSlotDuringForeignSpawnCannotOverwriteOrConsumeEitherStack() throws Exception {
        changedSource(true);
    }

    private void changedSource(boolean selectedSlot) throws Exception {
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var parent = f.fire();
            f.drain();
            assertEquals(List.of("pre event", "event", "construct", "particle", "spawn"), f.order);
            assertFalse(parent.isDone());
            var drain = ScarpetNativeWork.whenIdle(f.server);
            assertFalse(drain.isDone());
            var replacement = new ItemStack(Items.DRAGON_BREATH, 7);
            if (selectedSlot) when(f.inventory.getItem(0)).thenReturn(replacement);
            else when(f.world.getBlockEntity(f.sourcePos)).thenReturn(mock(DispenserBlockEntity.class));
            f.spawnChild.complete(null);
            f.drain();
            var failure = assertThrows(CompletionException.class, parent::join);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(2, f.stack.getCount());
            assertEquals(7, replacement.getCount());
            assertEquals("spawn", f.order.getLast());
            verify(f.stack, never()).shrink(anyInt());
            verify(f.inventory, never()).setItem(anyInt(), any());
            drain.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void guestOnlySpawnFailureRetainsRawParentAndRunsOriginalPhysicalCostTail() throws Exception {
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var parent = f.fire();
            f.drain();
            var guest = new IllegalArgumentException("guest spawn callback");
            ScarpetNativeWork.markGuestFailure(guest);
            f.spawnChild.completeExceptionally(guest);
            f.drain();
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
            assertEquals(1, f.stack.getCount());
            assertEquals("write", f.order.getLast());
        }
    }

    @Test
    void actualEventCallbackChildCanCancelBeforeSpawnInspectionWhileOriginalDefaultEffectsRemain() throws Exception {
        try (var f = new Fixture()) {
            f.eventChild = new CompletableFuture<>();
            var parent = f.fire();
            assertEquals(List.of("pre event", "event"), f.order);
            assertFalse(parent.isDone());
            f.event.setCancelled(true);
            f.eventChild.complete(null);
            f.drain();
            parent.join();
            assertTrue(f.clouds.constructed().isEmpty());
            assertEquals(List.of("pre event", "event", "sound", "animation", "write"), f.order);
            assertEquals(2, f.stack.getCount());
        }
    }

    @Test
    void originalFalseSpawnResultIsIgnoredAndNoCostRuleKeepsOriginalStackIdentity() throws Exception {
        try (var f = new Fixture()) {
            f.spawnResult = false;
            var parent = f.fire();
            f.drain();
            parent.join();
            assertEquals(1, f.stack.getCount());
        }
        try (var f = new Fixture()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.dispenserNoItemCost = true;
            var parent = f.fire();
            f.drain();
            parent.join();
            assertEquals(2, f.stack.getCount());
            verify(f.stack, never()).shrink(anyInt());
            verify(f.inventory, never()).setItem(anyInt(), any());
            assertEquals("animation", f.order.getLast());
        }
    }

    @Test
    void finalDefaultApiRecordsCompleteNativeReceiptAndPrivateCallerCannotCancelActualWork() throws Exception {
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var behavior = new CarpetDragonBreathDispenseBehavior();
            f.owner = f.sourcePos;
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                assertSame(f.stack, behavior.dispense(f.source, f.stack));
                return null;
            });
            f.owner = null;
            f.drain();
            assertFalse(parent.isDone());
            f.spawnChild.complete(null);
            f.drain();
            parent.join();
            assertEquals(List.of("event", "construct", "particle", "spawn", "shrink", "sound", "animation"), f.order);
        }
        try (var f = new Fixture()) {
            f.spawnChild = new CompletableFuture<>();
            var behavior = new CarpetDragonBreathDispenseBehavior();
            f.owner = f.sourcePos;
            var actual = behavior.carpetDispenseAsync(f.source, f.stack);
            f.owner = null;
            assertFalse(actual.cancel(true));
            f.drain();
            assertFalse(actual.isDone());
            f.spawnChild.complete(null);
            f.drain();
            assertSame(f.stack, actual.join());
        }
    }

    @Test
    void originalPreEventFalseStopsTheBehaviorAndSourceInventoryWrite() throws Exception {
        try (var f = new Fixture()) {
            f.pre.when(() -> CraftEventFactory.handleBlockPreDispenseEvent(f.world, f.sourcePos, f.stack, 0)).thenReturn(false);
            f.fire().join();
            assertTrue(f.order.isEmpty());
            assertTrue(f.clouds.constructed().isEmpty());
            verify(f.inventory, never()).setItem(anyInt(), any());
        }
    }

    @Test
    void creativeEnderChestReadsOriginalBlockedPredicateBeforeForcingItsResult() throws Exception {
        boolean prior = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOpenContainerForcibly;
        String force = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.forceOpenContainer;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOpenContainerForcibly = true;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.forceOpenContainer = "false";
            var world = mock(ServerLevel.class);
            var player = mock(net.minecraft.world.entity.player.Player.class);
            var pos = BlockPos.ZERO;
            var inventory = mock(net.minecraft.world.inventory.PlayerEnderChestContainer.class);
            var entity = mock(EnderChestBlockEntity.class);
            var state = mock(BlockState.class);
            var order = new ArrayList<String>();
            when(player.getEnderChestInventory()).thenReturn(inventory);
            when(world.getBlockEntity(pos)).thenReturn(entity);
            when(world.getBlockState(pos.above())).thenReturn(state);
            when(state.isRedstoneConductor(world, pos.above())).thenAnswer(call -> {
                order.add("predicate");
                return true;
            });
            when(player.isCreative()).thenAnswer(call -> {
                order.add("creative");
                return true;
            });
            when(player.openMenu(any())).thenAnswer(call -> {
                order.add("menu");
                return java.util.OptionalInt.empty();
            });
            Method method = EnderChestBlock.class.getDeclaredMethod("useWithoutItem", BlockState.class, net.minecraft.world.level.Level.class, BlockPos.class, net.minecraft.world.entity.player.Player.class, net.minecraft.world.phys.BlockHitResult.class);
            method.setAccessible(true);
            method.invoke(Blocks.ENDER_CHEST, Blocks.ENDER_CHEST.defaultBlockState(), world, pos, player, null);
            assertEquals(List.of("predicate", "creative", "menu"), order);
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOpenContainerForcibly = prior;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.forceOpenContainer = force;
        }
    }
}
