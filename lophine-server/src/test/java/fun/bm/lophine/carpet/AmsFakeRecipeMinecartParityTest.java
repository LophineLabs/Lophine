package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsFakeRecipeMinecartParityTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    boolean oldSurvival, oldExperimental, oldPumpkin;
    int oldSpeed;

    @BeforeEach
    void preserve() {
        oldSurvival = FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode;
        oldExperimental = GeneralCompatConfig.experimentalMinecartEnabled;
        oldPumpkin = GeneralCompatConfig.craftableCarvedPumpkin;
        oldSpeed = GeneralCompatConfig.experimentalMinecartSpeed;
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = false;
        GeneralCompatConfig.experimentalMinecartEnabled = false;
        GeneralCompatConfig.experimentalMinecartSpeed = -1;
        GeneralCompatConfig.craftableCarvedPumpkin = false;
    }

    @AfterEach
    void restore() {
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = oldSurvival;
        GeneralCompatConfig.experimentalMinecartEnabled = oldExperimental;
        GeneralCompatConfig.experimentalMinecartSpeed = oldSpeed;
        GeneralCompatConfig.craftableCarvedPumpkin = oldPumpkin;
    }

    static ServerPlayer sender(GameType mode, boolean flying) throws Exception {
        var sender = mock(ServerPlayer.class);
        var manager = mock(ServerPlayerGameMode.class);
        sender.gameMode = manager;
        when(manager.getGameModeForPlayer()).thenReturn(mode);
        var abilities = new Abilities();
        abilities.flying = flying;
        when(sender.getAbilities()).thenReturn(abilities);
        return sender;
    }

    @Test
    void defaultSurvivalKeepsTheOriginalConsoleCreativeMode() {
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = true;
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.CREATIVE, false), AmsFakePlayers.spawnMode(null, null));
    }

    @Test
    void defaultSurvivalWrapsOnlyPlayerModeAndStillReadsTheOriginalFlyingField() throws Exception {
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = true;
        var sender = sender(GameType.CREATIVE, true);
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.SURVIVAL, false), AmsFakePlayers.spawnMode(null, sender));
        verify(sender.gameMode, never()).getGameModeForPlayer();
        verify(sender).getAbilities();
    }

    @Test
    void explicitCreativeWithDefaultSurvivalStillClearsInheritedFlying() throws Exception {
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = true;
        var sender = sender(GameType.CREATIVE, true);
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.CREATIVE, false), AmsFakePlayers.spawnMode(GameType.CREATIVE, sender));
    }

    @Test
    void explicitSpectatorKeepsTheOriginalLaterFlyingOverride() throws Exception {
        FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = true;
        var sender = sender(GameType.CREATIVE, false);
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.SPECTATOR, true), AmsFakePlayers.spawnMode(GameType.SPECTATOR, sender));
    }

    @Test
    void disabledDefaultSurvivalKeepsOriginalPlayerModeAndFlying() throws Exception {
        var sender = sender(GameType.CREATIVE, true);
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.CREATIVE, true), AmsFakePlayers.spawnMode(null, sender));
        verify(sender.gameMode).getGameModeForPlayer();
    }

    @Test
    void laterExplicitSurvivalClearsInheritedPlayerFlying() throws Exception {
        var sender = sender(GameType.CREATIVE, true);
        assertEquals(new AmsFakePlayers.SpawnMode(GameType.SURVIVAL, false), AmsFakePlayers.spawnMode(GameType.SURVIVAL, sender));
    }

    @Test
    void actualExperimentalSpeedStillReadsVanillaRulesAndOriginalWaterBeforeItsReturnOverride() {
        GeneralCompatConfig.experimentalMinecartEnabled = true;
        GeneralCompatConfig.experimentalMinecartSpeed = 60;
        var world = mock(ServerLevel.class);
        var rules = mock(GameRules.class);
        var cart = mock(AbstractMinecart.class);
        when(world.getGameRules()).thenReturn(rules);
        when(rules.get(GameRules.MAX_MINECART_SPEED)).thenReturn(40);
        when(cart.isInWater()).thenReturn(false, true);
        assertEquals(1.5, new NewMinecartBehavior(cart).getMaxSpeed(world));
        verify(world).getGameRules();
        verify(rules).get(GameRules.MAX_MINECART_SPEED);
        verify(cart, times(2)).isInWater();
    }

    @Test
    void actualExperimentalSpeedRetainsTheOriginalBukkitMaxSpeedBranchBeforeOverride() {
        GeneralCompatConfig.experimentalMinecartEnabled = true;
        GeneralCompatConfig.experimentalMinecartSpeed = 60;
        var world = mock(ServerLevel.class);
        var cart = mock(AbstractMinecart.class);
        cart.maxSpeed = 3.0;
        when(cart.isInWater()).thenReturn(true, false);
        assertEquals(3.0, new NewMinecartBehavior(cart).getMaxSpeed(world));
        verify(world, never()).getGameRules();
        verify(cart, times(2)).isInWater();
    }

    @Test
    void actualExperimentalSpeedCannotSkipAnOriginalRuleFailure() {
        GeneralCompatConfig.experimentalMinecartEnabled = true;
        GeneralCompatConfig.experimentalMinecartSpeed = 60;
        var world = mock(ServerLevel.class);
        var failure = new IllegalStateException("original max speed rule");
        when(world.getGameRules()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new NewMinecartBehavior(mock(AbstractMinecart.class)).getMaxSpeed(world)));
    }

    @Test
    void actualExperimentalMovementAlwaysEvaluatesOriginalFeatureQuery() {
        GeneralCompatConfig.experimentalMinecartEnabled = true;
        var world = mock(Level.class);
        when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        assertTrue(AbstractMinecart.useExperimentalMovement(world));
        verify(world).enabledFeatures();
    }

    @Test
    void disabledExperimentalSpeedRetainsOriginalBukkitScalar() {
        var world = mock(ServerLevel.class);
        var cart = mock(AbstractMinecart.class);
        cart.maxSpeed = 3.0;
        when(cart.isInWater()).thenReturn(true);
        assertEquals(1.5, new NewMinecartBehavior(cart).getMaxSpeed(world));
        verify(cart).isInWater();
        verify(world, never()).getGameRules();
    }

    static ItemStack remainder(ShapelessRecipe recipe, ItemStack original) throws Exception {
        var method = ShapelessRecipe.class.getDeclaredMethod("carpetCarvedPumpkinShearsRemainder", ItemStack.class);
        method.setAccessible(true);
        return (ItemStack) method.invoke(recipe, original);
    }

    @SuppressWarnings("unchecked")
    static class Shears implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final RegistryAccess.Frozen access = mock(RegistryAccess.Frozen.class);
        final Registry<Enchantment> lookup = (Registry<Enchantment>) mock(Registry.class);
        final Holder.Reference<Enchantment> unbreaking = (Holder.Reference<Enchantment>) mock(Holder.Reference.class);
        final ItemStack input = mock(ItemStack.class), copy = mock(ItemStack.class);
        final org.mockito.MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<EnchantmentHelper> effects = mockStatic(EnchantmentHelper.class);
        final ShapelessRecipe recipe = mock(ShapelessRecipe.class);

        Shears(int level, int damage, int maximum) {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            when(server.registryAccess()).thenReturn(access);
            when(access.lookupOrThrow(Registries.ENCHANTMENT)).thenReturn(lookup);
            when(lookup.getOrThrow(Enchantments.UNBREAKING)).thenReturn(unbreaking);
            effects.when(() -> EnchantmentHelper.getItemEnchantmentLevel(unbreaking, input)).thenReturn(level);
            when(input.copy()).thenReturn(copy);
            when(copy.getDamageValue()).thenReturn(damage);
            when(copy.getMaxDamage()).thenReturn(maximum);
        }

        @Override
        public void close() {
            effects.close();
            servers.close();
        }
    }

    @Test
    void pumpkinSourceCreatesFreshRandomEvenWithoutUnbreakingAndDamagesOnlyTheCopy() throws Exception {
        try (var s = new Shears(0, 7, 9); var random = mockConstruction(java.util.Random.class)) {
            assertSame(s.copy, remainder(s.recipe, s.input));
            assertEquals(1, random.constructed().size());
            verify(random.constructed().getFirst(), never()).nextFloat();
            verify(s.copy).setCount(1);
            verify(s.copy).setDamageValue(8);
            verify(s.input, never()).setDamageValue(anyInt());
            verify(s.lookup).getOrThrow(Enchantments.UNBREAKING);
            s.effects.verify(() -> EnchantmentHelper.getItemEnchantmentLevel(s.unbreaking, s.input));
        }
    }

    @Test
    void pumpkinOriginalUnbreakingDrawProtectsItsCopyAndDoesNotApplyABrokenCheck() throws Exception {
        try (var s = new Shears(1, 8, 8); var random = mockConstruction(java.util.Random.class, (value, ctx) -> when(value.nextFloat()).thenReturn(0.1F))) {
            assertSame(s.copy, remainder(s.recipe, s.input));
            verify(random.constructed().getFirst()).nextFloat();
            verify(s.copy, never()).setDamageValue(anyInt());
            verify(s.copy, never()).getMaxDamage();
        }
    }

    @Test
    void pumpkinOriginalUnbreakingBoundaryDrawDamagesAndBreaksItsCopy() throws Exception {
        try (var s = new Shears(1, 7, 8); var random = mockConstruction(java.util.Random.class, (value, ctx) -> when(value.nextFloat()).thenReturn(0.5F))) {
            assertSame(ItemStack.EMPTY, remainder(s.recipe, s.input));
            verify(s.copy).setDamageValue(8);
            verify(s.copy).getMaxDamage();
        }
    }

    @Test
    void pumpkinCannotSkipOriginalMissingUnbreakingLookup() throws Exception {
        try (var s = new Shears(0, 0, 9); var random = mockConstruction(java.util.Random.class)) {
            var failure = new IllegalStateException("original unbreaking lookup");
            when(s.lookup.getOrThrow(Enchantments.UNBREAKING)).thenThrow(failure);
            assertSame(failure, assertThrows(InvocationTargetException.class, () -> remainder(s.recipe, s.input)).getCause());
            assertTrue(random.constructed().isEmpty());
        }
    }

    @Test
    void actualPumpkinRemainingItemsCallsOriginalRemaindersThenAssembleThenTheShearsBody() throws Exception {
        GeneralCompatConfig.craftableCarvedPumpkin = true;
        try (var s = new Shears(0, 7, 9); var random = mockConstruction(java.util.Random.class); var crafting = mockStatic(CraftingRecipe.class, CALLS_REAL_METHODS)) {
            var input = mock(CraftingInput.class);
            when(input.size()).thenReturn(1);
            when(input.getItem(0)).thenReturn(s.input);
            when(s.input.getItem()).thenReturn(Items.SHEARS);
            var assembled = mock(ItemStack.class);
            when(assembled.getItem()).thenReturn(Items.CARVED_PUMPKIN);
            var order = new ArrayList<String>();
            crafting.when(() -> CraftingRecipe.defaultCraftingReminder(input)).thenAnswer(call -> {
                order.add("original");
                return NonNullList.withSize(1, ItemStack.EMPTY);
            });
            when(s.recipe.assemble(input)).thenAnswer(call -> {
                order.add("assemble");
                return assembled;
            });
            doCallRealMethod().when(s.recipe).getRemainingItems(input);
            assertSame(s.copy, s.recipe.getRemainingItems(input).getFirst());
            assertEquals(List.of("original", "assemble"), order);
            assertEquals(1, random.constructed().size());
            verify(s.copy).setDamageValue(8);
        }
    }

    @Test
    void originalBoneAndDispenserFactoriesUseSourceIdsAndDefaultRecipeBookMetadata() throws Exception {
        var cases = Map.of("createBetterCraftableBoneBlockRecipe", "bone_block", "createBetterCraftableDispenserShapedRecipe", "dispenser1", "createBetterCraftableDispenserShapelessRecipe", "dispenser2");
        for (var item : cases.entrySet()) {
            var method = RecipeManager.class.getDeclaredMethod(item.getKey());
            method.setAccessible(true);
            var holder = (RecipeHolder<?>) method.invoke(null);
            assertEquals("carpetamsaddition:" + item.getValue(), holder.id().identifier().toString());
            var recipe = (NormalCraftingRecipe) holder.value();
            assertEquals(CraftingBookCategory.MISC, recipe.category());
            assertEquals("", recipe.group());
        }
    }
}
