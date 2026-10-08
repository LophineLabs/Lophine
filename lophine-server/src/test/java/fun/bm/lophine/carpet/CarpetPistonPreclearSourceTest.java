package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetPistonPreclearSourceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void fixedMoveClearsEverySourceInReverseBeforeDestroyOrMovement() throws Exception {
        move(true, false, false);
    }

    @Test
    void sourceSnapshotSurvivesRuleToggleDuringResolutionAndClearing() throws Exception {
        move(true, true, false);
    }

    @Test
    void disabledEntryKeepsOriginalMoveEvenWhenRuleTurnsOnLater() throws Exception {
        move(false, true, false);
    }

    @Test
    void rejectedPistonDoesNotClearOrExtractAnything() throws Exception {
        move(true, false, true);
    }

    @Test
    void anExtendedPushLimitRejectsAForwardForeignActorBeforeReadingItsBlocks() {
        resolveOwnership(false, false);
    }

    @Test
    void aStickyBranchRejectsAForeignActorBeforeReadingItsBlocks() {
        resolveOwnership(true, false);
    }

    @Test
    void anOwnedPistonStillResolvesMoreThanTwelveBlocks() {
        resolveOwnership(false, true);
    }

    private void resolveOwnership(boolean sticky, boolean allOwned) {
        int before = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pushLimit;
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.unsupportedSettings = config.new UnsupportedSettings();
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class); var configurations = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pushLimit = 48;
            var world = mock(ServerLevel.class);
            var border = mock(net.minecraft.world.level.border.WorldBorder.class);
            when(world.getWorldBorder()).thenReturn(border);
            when(border.isWithinBounds(any(BlockPos.class))).thenReturn(true);
            when(world.getMinY()).thenReturn(-64);
            when(world.getMaxY()).thenReturn(320);
            java.util.function.Predicate<BlockPos> owned = position -> allOwned || (sticky ? position.getZ() == 0 : position.getX() < 17);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> owned.test(call.getArgument(1)));
            when(world.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
                BlockPos position = call.getArgument(0);
                assertTrue(owned.test(position), "Resolver crossed into a foreign block actor at " + position);
                if (sticky)
                    return position.equals(BlockPos.ZERO.east()) ? Blocks.SLIME_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                return position.getY() == 0 && position.getZ() == 0 && position.getX() >= 1 && position.getX() <= 32 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            });
            var resolver = new PistonStructureResolver(world, BlockPos.ZERO, Direction.EAST, true);
            assertEquals(allOwned, resolver.resolve());
            if (allOwned) {
                assertEquals(32, resolver.getToPush().size());
                assertEquals(BlockPos.ZERO.offset(32, 0, 0), resolver.getToPush().getLast());
            }
            verify(world, never()).setBlock(any(), any(), anyInt());
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pushLimit = before;
        }
    }

    private void move(boolean enabled, boolean toggle, boolean cancelled) throws Exception {
        boolean before = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix;
        boolean carriedBefore = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix = enabled;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities = false;
            var world = mock(ServerLevel.class);
            var piston = BlockPos.ZERO;
            var first = piston.east();
            var second = first.east();
            var destroyed = second.east();
            var initial = Blocks.STONE.defaultBlockState();
            var fresh = Blocks.OBSERVER.defaultBlockState();
            var map = new HashMap<BlockPos, BlockState>();
            map.put(first, initial);
            map.put(second, initial);
            map.put(destroyed, Blocks.FIRE.defaultBlockState());
            when(world.getBlockState(any(BlockPos.class))).thenAnswer(call -> map.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
            var events = new ArrayList<String>();
            var movedStates = new ArrayList<BlockState>();
            when(world.setBlock(any(BlockPos.class), any(BlockState.class), anyInt())).thenAnswer(call -> {
                BlockPos at = call.getArgument(0);
                BlockState state = call.getArgument(1);
                int flags = call.getArgument(2);
                if (flags == 86) {
                    events.add("clear:" + at.getX());
                    if (toggle) fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix = false;
                }
                if (state.is(Blocks.MOVING_PISTON)) events.add("move:" + at.getX());
                if (at.equals(destroyed) && state.isAir()) events.add("destroy");
                map.put(at, state);
                return true;
            });
            var updates = new HashMap<BlockPos, Block>();
            doAnswer(call -> {
                updates.putIfAbsent(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(world).updateNeighborsAt(any(BlockPos.class), any(Block.class), any());
            try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class); var resolver = mockConstruction(PistonStructureResolver.class, (actual, context) -> {
                when(actual.resolve()).thenAnswer(call -> {
                    if (toggle) fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix = !enabled;
                    return true;
                });
                when(actual.getToPush()).thenReturn(List.of(first, second));
                when(actual.getToDestroy()).thenReturn(List.of(destroyed));
                when(actual.getPushDirection()).thenReturn(Direction.EAST);
            }); var craft = mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class); var event = mockConstruction(org.bukkit.event.block.BlockPistonExtendEvent.class, (actual, context) -> {
                when(actual.callEvent()).thenAnswer(call -> {
                    map.put(first, fresh);
                    map.put(second, fresh);
                    return !cancelled;
                });
            }); var moving = mockStatic(MovingPistonBlock.class); var micro = mockStatic(TisMicroTimingMarkers.class); var redstone = mockStatic(net.minecraft.world.level.redstone.ExperimentalRedstoneUtils.class); var block = mockStatic(Block.class)) {
                ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
                craft.when(() -> org.bukkit.craftbukkit.block.CraftBlock.at(eq(world), any(BlockPos.class))).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlock.class));
                craft.when(() -> org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(any(Direction.class))).thenReturn(org.bukkit.block.BlockFace.EAST);
                moving.when(() -> MovingPistonBlock.newMovingBlockEntity(any(BlockPos.class), any(BlockState.class), any(BlockState.class), any(Direction.class), anyBoolean(), anyBoolean())).thenAnswer(call -> {
                    if (!(boolean) call.getArgument(5)) movedStates.add(call.getArgument(2));
                    return mock(PistonMovingBlockEntity.class);
                });
                var nativeMethod = PistonBaseBlock.class.getDeclaredMethod("moveBlocks", net.minecraft.world.level.Level.class, BlockPos.class, Direction.class, boolean.class);
                nativeMethod.setAccessible(true);
                assertEquals(!cancelled, nativeMethod.invoke(Blocks.PISTON, world, piston, Direction.EAST, true));
                if (cancelled) {
                    assertTrue(events.isEmpty());
                    assertTrue(movedStates.isEmpty());
                    verify(world, never()).removeBlockEntity(any());
                    return;
                }
                if (enabled) {
                    assertEquals(List.of("clear:2", "clear:1"), events.subList(0, 2));
                    assertTrue(events.indexOf("destroy") > 1);
                    assertEquals(List.of(fresh, fresh), movedStates);
                    assertSame(Blocks.OBSERVER, updates.get(first));
                    assertSame(Blocks.OBSERVER, updates.get(second));
                } else {
                    assertFalse(events.stream().anyMatch(value -> value.startsWith("clear:")));
                    assertEquals(List.of(initial, initial), movedStates);
                }
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix = before;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities = carriedBefore;
        }
    }
}
