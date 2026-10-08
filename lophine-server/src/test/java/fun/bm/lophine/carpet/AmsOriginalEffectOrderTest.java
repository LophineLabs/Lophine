package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsOriginalEffectOrderTest {
    @BeforeAll
    static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var configurations = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            Class.forName("net.minecraft.network.Connection");
        }
    }

    @TempDir
    Path directory;
    String oldBundle;
    boolean oldPortal, oldString, oldLuminol, oldVanilla;

    @BeforeEach
    void preserve() {
        oldBundle = GeneralCompatConfig.largeBundle;
        oldPortal = GeneralCompatConfig.customizedNetherPortal;
        oldString = GeneralCompatConfig.stringDupeReintroduced;
        oldLuminol = me.earthme.luminol.config.modules.function.TripwireBehaviorConfig.enabled;
        oldVanilla = fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled;
        GeneralCompatConfig.largeBundle = "false";
        GeneralCompatConfig.customizedNetherPortal = false;
        GeneralCompatConfig.stringDupeReintroduced = false;
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.largeBundle = oldBundle;
        GeneralCompatConfig.customizedNetherPortal = oldPortal;
        GeneralCompatConfig.stringDupeReintroduced = oldString;
        me.earthme.luminol.config.modules.function.TripwireBehaviorConfig.enabled = oldLuminol;
        fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled = oldVanilla;
    }

    static BlockState shape(NetherPortalBlock block, BlockState state, LevelReader level, BlockState neighbour) throws Exception {
        var method = NetherPortalBlock.class.getDeclaredMethod("updateShape", BlockState.class, LevelReader.class, ScheduledTickAccess.class, BlockPos.class, Direction.class, BlockPos.class, BlockState.class, RandomSource.class);
        method.setAccessible(true);
        return (BlockState) method.invoke(block, state, level, mock(ScheduledTickAccess.class), BlockPos.ZERO, Direction.EAST, BlockPos.ZERO.east(), neighbour, mock(RandomSource.class));
    }

    @Test
    void customizedPortalActualReturnStillQueriesOriginalShapeBeforeReadingKeptState() throws Exception {
        GeneralCompatConfig.customizedNetherPortal = true;
        var level = mock(LevelReader.class);
        var order = new ArrayList<String>();
        var kept = Blocks.NETHER_PORTAL.defaultBlockState();
        var scanned = mock(PortalShape.class);
        when(level.getBlockState(BlockPos.ZERO)).thenAnswer(call -> {
            order.add("kept");
            return kept;
        });
        try (var shapes = mockStatic(PortalShape.class)) {
            shapes.when(() -> PortalShape.findAnyShape(level, BlockPos.ZERO, Direction.Axis.X)).thenAnswer(call -> {
                order.add("scan");
                return scanned;
            });
            assertSame(kept, shape((NetherPortalBlock) Blocks.NETHER_PORTAL, kept, level, Blocks.STONE.defaultBlockState()));
            assertEquals(List.of("scan", "kept"), order);
        }
    }

    @Test
    void defaultPortalActualReturnRetainsTheOriginalIncompleteShapeAir() throws Exception {
        var level = mock(LevelReader.class);
        var state = Blocks.NETHER_PORTAL.defaultBlockState();
        var scanned = mock(PortalShape.class);
        try (var shapes = mockStatic(PortalShape.class)) {
            shapes.when(() -> PortalShape.findAnyShape(level, BlockPos.ZERO, Direction.Axis.X)).thenReturn(scanned);
            assertSame(Blocks.AIR.defaultBlockState(), shape((NetherPortalBlock) Blocks.NETHER_PORTAL, state, level, Blocks.STONE.defaultBlockState()));
            verify(level, never()).getBlockState(BlockPos.ZERO);
        }
    }

    @Test
    void actualBundleSoundKeepsOriginalSourcePlayerAndPrecedesMenu() {
        GeneralCompatConfig.largeBundle = "9x3";
        var level = mock(ServerLevel.class);
        var player = mock(Player.class);
        var stack = mock(ItemStack.class);
        var order = new ArrayList<String>();
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(stack);
        when(stack.getHoverName()).thenReturn(net.minecraft.network.chat.Component.literal("actual bundle"));
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        doAnswer(call -> {
            order.add("sound");
            return null;
        }).when(level).playSound(eq(player), eq(BlockPos.ZERO), eq(net.minecraft.sounds.SoundEvents.BUNDLE_DROP_CONTENTS), eq(net.minecraft.sounds.SoundSource.PLAYERS), eq(1.5F), eq(1.35F));
        doAnswer(call -> {
            order.add("menu");
            return OptionalInt.empty();
        }).when(player).openMenu(any(MenuProvider.class));
        var item = mock(BundleItem.class);
        doCallRealMethod().when(item).use(level, player, InteractionHand.MAIN_HAND);
        assertEquals(InteractionResult.SUCCESS, item.use(level, player, InteractionHand.MAIN_HAND));
        assertEquals(List.of("sound", "menu"), order);
        verify(player, never()).startUsingItem(any());
    }

    @Test
    void actualNativeBundleSoundAndMenuChildrenFenceTheFinalSourceResult() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            GeneralCompatConfig.largeBundle = "9x6";
            var stack = mock(ItemStack.class);
            when(stack.getHoverName()).thenReturn(net.minecraft.network.chat.Component.literal("actual bundle"));
            when(f.sourcePlayer.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(stack);
            var sound = new CompletableFuture<Void>();
            var menu = new CompletableFuture<Void>();
            doAnswer(call -> {
                f.order.add("sound");
                ScarpetNativeWork.record(sound);
                return null;
            }).when(f.world).playSound(eq(f.sourcePlayer), any(BlockPos.class), eq(net.minecraft.sounds.SoundEvents.BUNDLE_DROP_CONTENTS), eq(net.minecraft.sounds.SoundSource.PLAYERS), eq(1.5F), eq(1.35F));
            doAnswer(call -> {
                f.order.add("menu");
                ScarpetNativeWork.record(menu);
                return OptionalInt.empty();
            }).when(f.sourcePlayer).openMenu(any(MenuProvider.class));
            var item = mock(BundleItem.class);
            doCallRealMethod().when(item).use(f.world, f.sourcePlayer, InteractionHand.MAIN_HAND);
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> item.use(f.world, f.sourcePlayer, InteractionHand.MAIN_HAND));
            f.drain();
            assertEquals(List.of("sound"), f.order);
            assertFalse(actual.isDone());
            sound.complete(null);
            f.drain();
            assertEquals(List.of("sound", "menu"), f.order);
            assertFalse(actual.isDone());
            menu.complete(null);
            f.drain();
            assertEquals(InteractionResult.SUCCESS, actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualNativeBundleSoundFailurePreventsMenu() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            GeneralCompatConfig.largeBundle = "9x6";
            var stack = mock(ItemStack.class);
            when(stack.getHoverName()).thenReturn(net.minecraft.network.chat.Component.literal("actual bundle"));
            var sound = new CompletableFuture<Void>();
            var failure = new IllegalStateException("actual sound");
            doAnswer(call -> {
                ScarpetNativeWork.record(sound);
                return null;
            }).when(f.world).playSound(eq(f.sourcePlayer), any(BlockPos.class), eq(net.minecraft.sounds.SoundEvents.BUNDLE_DROP_CONTENTS), eq(net.minecraft.sounds.SoundSource.PLAYERS), eq(1.5F), eq(1.35F));
            var actual = AmsLargeBundleContainer.open(f.world, f.sourcePlayer, stack);
            f.drain();
            assertFalse(actual.cancel(false));
            sound.completeExceptionally(failure);
            f.drain();
            assertSame(failure, assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            verify(f.sourcePlayer, never()).openMenu(any());
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void actualBundleContainerCopiesIncomingStacksWithoutClampingAndPersistsEmptyRemoval() throws Exception {
        var player = mock(Player.class);
        var inventory = mock(net.minecraft.world.entity.player.Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var bundle = mock(ItemStack.class);
        var container = new AmsLargeBundleContainer(bundle, player);
        var constructor = ItemStack.class.getDeclaredConstructor(Holder.class, int.class, PatchedDataComponentMap.class);
        constructor.setAccessible(true);
        var components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        var input = constructor.newInstance(Items.STONE.builtInRegistryHolder(), 100, new PatchedDataComponentMap(components));
        container.setItem(0, input);
        assertNotSame(input, container.getItem(0));
        assertEquals(100, container.getItem(0).getCount());
        assertEquals(100, input.getCount());
        input.setCount(12);
        assertEquals(100, container.getItem(0).getCount());
        assertTrue(container.removeItem(0, 0).isEmpty());
        verify(inventory, times(2)).setChanged();
    }

    @Test
    void stringDupeActualTailReadsTheCurrentWireAndOriginalIsBeforeUpdatingIt() throws Exception {
        tripwire(true);
    }

    @Test
    void stringDupeDisabledRetainsTheActualNonWireSkip() throws Exception {
        tripwire(false);
    }

    static void tripwire(boolean enabled) {
        GeneralCompatConfig.stringDupeReintroduced = enabled;
        me.earthme.luminol.config.modules.function.TripwireBehaviorConfig.enabled = false;
        fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled = false;
        var level = mock(Level.class);
        var order = new ArrayList<String>();
        var at = BlockPos.ZERO;
        var wire = at.east();
        var receiver = at.east(2);
        var state = Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.EAST).setValue(TripWireHookBlock.ATTACHED, false);
        var receiverState = Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.WEST);
        var wireState = Blocks.TRIPWIRE.defaultBlockState();
        var current = mock(BlockState.class);
        when(level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        when(level.getBlockState(at)).thenReturn(state);
        when(level.getBlockState(receiver)).thenReturn(receiverState);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(level.getBlockState(wire)).thenAnswer(call -> {
            if (reads.getAndIncrement() == 0) return wireState;
            order.add("read");
            return current;
        });
        when(current.is(Blocks.TRIPWIRE)).thenAnswer(call -> {
            order.add("is");
            return false;
        });
        when(level.setBlockAndUpdate(eq(wire), any(BlockState.class))).thenAnswer(call -> {
            order.add("write");
            return true;
        });
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.unsupportedSettings = config.new UnsupportedSettings();
        config.blockUpdates = config.new BlockUpdates();
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class); var configs = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(level), any(BlockPos.class), eq(4))).thenReturn(true);
            configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            TripWireHookBlock.calculateState(level, at, state, false, false, -1, null);
            assertEquals(enabled ? List.of("read", "is", "write") : List.of("read", "is"), order);
            verify(level, never()).setBlock(eq(wire), any(), eq(3));
        }
    }
}
