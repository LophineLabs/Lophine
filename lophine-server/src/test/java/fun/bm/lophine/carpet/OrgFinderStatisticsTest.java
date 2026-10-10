package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgFinderStatisticsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (var item : List.of(Items.STONE, Items.SHULKER_BOX, Items.BUNDLE)) {
            try {
                item.builtInRegistryHolder().components();
            } catch (NullPointerException unbound) {
                item.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
            }
        }
    }

    @Test
    void nestedBoxesUseEachContainerCountAndBundlesAreIncludedLikeThePinnedSource() {
        var inner = new ItemStack(Items.SHULKER_BOX, 2);
        inner.set(DataComponents.MAX_STACK_SIZE, 64);
        inner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 5))));
        var outer = new ItemStack(Items.SHULKER_BOX, 3);
        outer.set(DataComponents.MAX_STACK_SIZE, 64);
        outer.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 4), inner)));
        var bundle = new ItemStack(Items.BUNDLE, 2);
        bundle.set(DataComponents.MAX_STACK_SIZE, 64);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.STONE, 6)))));
        var stats = OrgFinderStatistics.count(new SimpleContainer(outer, bundle), stack -> stack.is(Items.STONE));
        assertEquals(34, stats.total());
        assertEquals(34, stats.counts().get(Items.STONE));
        assertTrue(stats.nested().contains(Items.STONE));
        assertEquals(3, outer.getCount());
        assertEquals(2, inner.getCount());
    }

    @Test
    void cornerAdjacentMatchesConnectThroughOtherMatchedBlockTypes() {
        var matches = new HashMap<BlockPos, net.minecraft.world.level.block.Block>();
        matches.put(new BlockPos(0, 0, 0), Blocks.STONE);
        matches.put(new BlockPos(1, 1, 1), Blocks.DIRT);
        matches.put(new BlockPos(2, 2, 2), Blocks.STONE);
        var groups = OrgFinderStatistics.groups(matches, BlockPos.ZERO);
        assertEquals(2, groups.size());
        assertEquals(Blocks.STONE, groups.getFirst().block());
        assertEquals(2, groups.getFirst().positions().size());
    }

    @Test
    void representativeIsAnExistingMemberNearestTheBoundingBoxMidpoint() {
        var matches = new HashMap<BlockPos, net.minecraft.world.level.block.Block>();
        for (int x = 0; x <= 100; x++) matches.put(new BlockPos(x, 0, 0), Blocks.DIRT);
        for (int x : new int[]{0, 1, 2, 40, 41, 100}) matches.put(new BlockPos(x, 0, 0), Blocks.STONE);
        var stone = OrgFinderStatistics.groups(matches, BlockPos.ZERO).stream().filter(group -> group.block() == Blocks.STONE).findFirst().orElseThrow();
        assertEquals(new BlockPos(41, 0, 0), stone.center());
        assertTrue(stone.positions().contains(stone.center()));
    }

    @Test
    void aRadiusQueryUsesTheWholeConstructionHeightAndTheInclusiveHorizontalLimit() {
        var world = mock(ServerLevel.class);
        when(world.getMinY()).thenReturn(-64);
        when(world.getMaxY()).thenReturn(319);
        var bounds = OrgFinderBounds.radius(world, new BlockPos(5, 100, -5), 512);
        assertEquals(-64, bounds.minY());
        assertEquals(319, bounds.maxY());
        assertEquals(1025, bounds.maxX() - bounds.minX() + 1);
        assertTrue(bounds.contains(new BlockPos(5, 319, -5)));
        assertThrows(IllegalArgumentException.class, () -> OrgFinderBounds.of(world, BlockPos.ZERO, new BlockPos(1025, 1, 1)));
    }
}
