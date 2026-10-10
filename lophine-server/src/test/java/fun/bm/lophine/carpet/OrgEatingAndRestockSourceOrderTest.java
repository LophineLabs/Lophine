package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Original floating point threshold and tick HEAD restock relative to real Carpet action producer.
 */
public class OrgEatingAndRestockSourceOrderTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void originalDoubleThresholdAcceptsRepresentableLowHealthBoundaryAndKeepsSaturationAndRuleGuards() throws Exception {
        boolean old = GeneralCompatConfig.healthNotFullCanEat;
        GeneralCompatConfig.healthNotFullCanEat = true;
        try {
            Player player = mock(Player.class, CALLS_REAL_METHODS);
            var abilities = Player.class.getDeclaredField("abilities");
            abilities.setAccessible(true);
            abilities.set(player, new net.minecraft.world.entity.player.Abilities());
            doReturn(1F).when(player).getMaxHealth();
            doReturn(.7F).when(player).getHealth();
            var food = mock(FoodData.class);
            var field = Player.class.getDeclaredField("foodData");
            field.setAccessible(true);
            field.set(player, food);
            when(food.getSaturationLevel()).thenReturn(5F);
            assertTrue(player.canEat(false));
            when(food.getSaturationLevel()).thenReturn(Math.nextUp(5F));
            assertFalse(player.canEat(false));
            when(food.getSaturationLevel()).thenReturn(5F);
            GeneralCompatConfig.healthNotFullCanEat = false;
            assertFalse(player.canEat(false));
        } finally {
            GeneralCompatConfig.healthNotFullCanEat = old;
        }
    }

    @Test
    void actualServerPlayerTickRestocksBeforeCarpetActionAndPreservesComponentsAndDisabledRule() throws Exception {
        boolean old = GeneralCompatConfig.forceRestock;
        GeneralCompatConfig.forceRestock = true;
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            var inventory = fixture.target.inventory();
            inventory.setItem(0, new ItemStack(Items.DIAMOND, 10));
            inventory.setItem(9, new ItemStack(Items.DIAMOND, 20));
            inventory.setItem(10, new ItemStack(Items.EMERALD, 8));
            var actions = mock(CarpetPlayerActionPack.class);
            var field = net.minecraft.server.level.ServerPlayer.class.getField("carpetActionPack");
            field.setAccessible(true);
            field.set(player, actions);
            doCallRealMethod().when(player).tick();
            var reached = new IllegalStateException("reached original native action phase");
            doAnswer(call -> {
                assertEquals(30, inventory.getItem(0).getCount());
                assertTrue(inventory.getItem(9).isEmpty());
                assertEquals(8, inventory.getItem(10).getCount());
                throw reached;
            }).when(actions).onUpdate();
            assertSame(reached, assertThrows(IllegalStateException.class, player::tick));
            inventory.setItem(0, new ItemStack(Items.DIAMOND, 10));
            inventory.setItem(9, new ItemStack(Items.DIAMOND, 20));
            GeneralCompatConfig.forceRestock = false;
            doAnswer(call -> {
                assertEquals(10, inventory.getItem(0).getCount());
                assertEquals(20, inventory.getItem(9).getCount());
                throw reached;
            }).when(actions).onUpdate();
            assertSame(reached, assertThrows(IllegalStateException.class, player::tick));
        } finally {
            GeneralCompatConfig.forceRestock = old;
        }
    }
}
