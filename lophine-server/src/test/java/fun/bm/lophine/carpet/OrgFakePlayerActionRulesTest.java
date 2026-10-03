package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrgFakePlayerActionRulesTest {
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    @Test void inputSupplyReservesOneStackableItemAndNeverReservesAnUnstackableTool() {
        boolean previous = GeneralCompatConfig.fakePlayerActionKeepItem;
        try {
            ItemStack stackable = new ItemStack(Items.EMERALD, 5); ItemStack singleton = new ItemStack(Items.EMERALD, 1);
            ItemStack tool = mock(ItemStack.class); when(tool.getCount()).thenReturn(1); when(tool.getMaxStackSize()).thenReturn(1);
            GeneralCompatConfig.fakePlayerActionKeepItem = true;
            assertEquals(4, OrgFakePlayerActions.transferable(stackable)); assertEquals(0, OrgFakePlayerActions.transferable(singleton)); assertEquals(1, OrgFakePlayerActions.transferable(tool));
            GeneralCompatConfig.fakePlayerActionKeepItem = false;
            assertEquals(5, OrgFakePlayerActions.transferable(stackable)); assertEquals(1, OrgFakePlayerActions.transferable(singleton));
        } finally { GeneralCompatConfig.fakePlayerActionKeepItem = previous; }
    }
    @Test void aRecipeInEachCornerUsesItsCorrespondingInventoryGridPositions() {
        int[][] positions = {{3,4,6,7}, {1,2,4,5}, {0,1,3,4}, {4,5,7,8}};
        for (int[] indices : positions) {
            List<Item> recipe = new ArrayList<>(java.util.Collections.nCopies(9, Items.AIR));
            recipe.set(indices[0], Items.EMERALD); recipe.set(indices[1], Items.GOLD_INGOT); recipe.set(indices[2], Items.DIAMOND); recipe.set(indices[3], Items.EMERALD);
            var action = OrgFakePlayerRecipeMenus.craft(recipe);
            assertEquals("craft_inventory", action.kind()); assertEquals(4, action.filters().size());
            for (int index = 0; index < 4; index++) assertTrue(action.filters().get(index).test(new ItemStack(recipe.get(indices[index]))));
        }
    }
    @Test void aRecipeSpanningThreeColumnsRetainsTheNineSlotRecipeAndEmptyPredicates() {
        List<Item> recipe = new ArrayList<>(java.util.Collections.nCopies(9, Items.AIR)); recipe.set(0, Items.EMERALD); recipe.set(2, Items.GOLD_INGOT);
        var action = OrgFakePlayerRecipeMenus.craft(recipe);
        assertEquals("craft_table", action.kind()); assertEquals(9, action.filters().size()); assertTrue(action.filters().get(1).test(ItemStack.EMPTY)); assertFalse(action.filters().get(0).test(ItemStack.EMPTY));
    }
}
