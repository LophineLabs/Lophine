package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarriedBlockEntityPlacementTest {
    @BeforeAll
    static void initializeRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void pendingInstanceIsConsumedOnceOnlyAtItsWorldAndPosition() {
        Level level = mock(Level.class);
        Level other = mock(Level.class);
        BlockEntity entity = mock(BlockEntity.class);
        BlockState state = mock(BlockState.class);
        BlockPos pos = new BlockPos(12, 64, 13);
        when(entity.isValidBlockState(state)).thenReturn(true);
        try (CarriedBlockEntityPlacement.Scope ignored = CarriedBlockEntityPlacement.begin(level, pos, entity)) {
            assertNull(CarriedBlockEntityPlacement.take(other, pos, state));
            assertNull(CarriedBlockEntityPlacement.take(level, pos.above(), state));
            assertSame(entity, CarriedBlockEntityPlacement.take(level, pos, state));
            assertNull(CarriedBlockEntityPlacement.take(level, pos, state));
        }
        assertNull(CarriedBlockEntityPlacement.take(level, pos, state));
        verify(entity).carpetSetPosition(pos);
        verify(entity).clearRemoved();
    }

    @Test
    void nestedPlacementFailureRestoresTheOuterInstance() {
        Level level = mock(Level.class);
        BlockEntity outer = mock(BlockEntity.class);
        BlockEntity inner = mock(BlockEntity.class);
        BlockState state = mock(BlockState.class);
        BlockPos pos = BlockPos.ZERO;
        when(outer.isValidBlockState(state)).thenReturn(true);
        try (CarriedBlockEntityPlacement.Scope ignored = CarriedBlockEntityPlacement.begin(level, pos, outer)) {
            assertThrows(IllegalStateException.class, () -> {
                try (CarriedBlockEntityPlacement.Scope nested = CarriedBlockEntityPlacement.begin(level, pos, inner)) {
                    throw new IllegalStateException("simulated block callback failure");
                }
            });
            assertSame(outer, CarriedBlockEntityPlacement.take(level, pos, state));
        }
    }

    @Test
    void movingChestRetainsInventoryAndComponentsAcrossNbtReloadWithRuleDisabled() {
        BlockPos source = new BlockPos(11, 64, 13);
        BlockPos destination = source.east();
        var chestState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
        var pistonState = net.minecraft.world.level.block.Blocks.MOVING_PISTON.defaultBlockState();
        var chest = new net.minecraft.world.level.block.entity.ChestBlockEntity(source, chestState);
        // Unit fixture: production binds item components during data pack loading.
        net.minecraft.world.item.Items.DIAMOND.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        var diamonds = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 37);
        diamonds.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("carried diamonds"));
        chest.setItem(4, diamonds);
        var moving = new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(destination, pistonState,
                chestState, net.minecraft.core.Direction.EAST, true, false);
        moving.carpetSetCarriedBlockEntity(chest);
        assertSame(chest, moving.carpetGetCarriedBlockEntity());
        assertEquals(destination, chest.getBlockPos());

        var lookup = net.minecraft.core.HolderLookup.Provider.create(net.minecraft.core.registries.BuiltInRegistries.REGISTRY.stream()
                .map(registry -> (net.minecraft.core.HolderLookup.RegistryLookup<?>) registry));
        var tag = moving.saveWithoutMetadata(lookup);
        assertTrue(tag.contains("carriedTileEntityCM"));
        var reloaded = new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(destination, pistonState);
        reloaded.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, lookup, tag));
        assertInstanceOf(net.minecraft.world.level.block.entity.ChestBlockEntity.class, reloaded.carpetGetCarriedBlockEntity());
        var loadedChest = (net.minecraft.world.level.block.entity.ChestBlockEntity) reloaded.carpetGetCarriedBlockEntity();
        assertTrue(net.minecraft.world.item.ItemStack.matches(diamonds, loadedChest.getItem(4)));
        assertEquals(destination, loadedChest.getBlockPos());
    }
}
