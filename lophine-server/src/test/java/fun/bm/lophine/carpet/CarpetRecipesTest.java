package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetRecipesTest {
    private List<Boolean> previous;

    @BeforeAll
    static void initializeRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void rememberRules() {
        this.previous = GeneralCompatConfig.recipeRuleValues();
        setRules(List.of(false, false, false, false, false, false, false));
    }

    @AfterEach
    void restoreRules() {
        setRules(this.previous);
    }

    @Test
    void recipeRuleChangesAddAndRemoveAllEightRecipes() {
        RecipeManager manager = emptyManager();
        assertEquals(0, manager.recipes.values().size());
        setRules(List.of(true, true, true, true, true, true, true));
        manager.carpetReloadCompatRecipes();
        assertEquals(8, manager.recipes.values().size());
        RecipeHolder<?> leather = manager.recipes.byKey(key("leather"));
        SmeltingRecipe smelting = assertInstanceOf(SmeltingRecipe.class, leather.value());
        assertEquals(50, smelting.cookingTime());
        assertEquals(0.1F, smelting.experience());
        manager.carpetReloadCompatRecipes();
        assertEquals(8, manager.recipes.values().size());
        setRules(List.of(false, false, false, false, false, false, false));
        manager.carpetReloadCompatRecipes();
        assertEquals(0, manager.recipes.values().size());
    }

    @Test
    void disablingARulePreservesAPluginReplacementAtTheSameKey() {
        GeneralCompatConfig.craftableElytra = true;
        RecipeManager manager = emptyManager();
        RecipeHolder<?> original = manager.recipes.byKey(key("elytra"));
        RecipeHolder<?> replacement = new RecipeHolder<>(original.id(), original.value());
        manager.recipes.removeRecipe(original.id());
        manager.recipes.addRecipe(replacement);
        GeneralCompatConfig.craftableElytra = false;
        manager.carpetReloadCompatRecipes();
        assertSame(replacement, manager.recipes.byKey(original.id()));
    }

    @Test
    void carvedPumpkinReturnsDamagedShearsWithoutChangingTheInput() {
        GeneralCompatConfig.craftableCarvedPumpkin = true;
        var shearsComponents = net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1)
                .set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 238).build();
        net.minecraft.world.item.Items.SHEARS.builtInRegistryHolder().bindComponents(shearsComponents);
        net.minecraft.world.item.Items.PUMPKIN.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        net.minecraft.world.item.Items.CARVED_PUMPKIN.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var access = mock(net.minecraft.core.RegistryAccess.Frozen.class);
        @SuppressWarnings("unchecked") var enchantments = (net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment>) mock(net.minecraft.core.Registry.class);
        @SuppressWarnings("unchecked") var unbreaking = (net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment>) mock(net.minecraft.core.Holder.Reference.class);
        when(server.registryAccess()).thenReturn(access);
        when(access.lookupOrThrow(Registries.ENCHANTMENT)).thenReturn(enchantments);
        when(enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING)).thenReturn(unbreaking);
        try (var servers = mockStatic(net.minecraft.server.MinecraftServer.class)) {
            servers.when(net.minecraft.server.MinecraftServer::getServer).thenReturn(server);
            var shears = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS);
            var pumpkin = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PUMPKIN);
            CraftingInput input = CraftingInput.of(2, 1, List.of(shears, pumpkin));
            ShapelessRecipe recipe = (ShapelessRecipe) emptyManager().recipes.byKey(key("carved_pumpkin")).value();
            var remainders = recipe.getRemainingItems(input);
            assertEquals(1, remainders.get(0).getDamageValue());
            assertEquals(0, shears.getDamageValue());
            assertFalse(remainders.get(0).isEmpty());
            shears.setDamageValue(237);
            assertTrue(recipe.getRemainingItems(input).get(0).isEmpty());
        }
    }

    private static ResourceKey<Recipe<?>> key(String name) {
        return ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("carpetamsaddition", name));
    }

    @SuppressWarnings("unchecked")
    private static RecipeManager emptyManager() {
        HolderLookup.RegistryLookup<Recipe<?>> recipes = mock(HolderLookup.RegistryLookup.class);
        doReturn(Registries.RECIPE).when(recipes).key();
        when(recipes.listElements()).thenAnswer(invocation -> Stream.empty());
        return new RecipeManager(HolderLookup.Provider.create(Stream.of(recipes)));
    }

    private static void setRules(List<Boolean> values) {
        GeneralCompatConfig.betterCraftableBoneBlock = values.get(0);
        GeneralCompatConfig.betterCraftableDispenser = values.get(1);
        GeneralCompatConfig.craftableEnchantedGoldenApples = values.get(2);
        GeneralCompatConfig.craftableElytra = values.get(3);
        GeneralCompatConfig.betterCraftablePolishedBlackStoneButton = values.get(4);
        GeneralCompatConfig.rottenFleshBurnedIntoLeather = values.get(5);
        GeneralCompatConfig.craftableCarvedPumpkin = values.get(6);
    }
}
