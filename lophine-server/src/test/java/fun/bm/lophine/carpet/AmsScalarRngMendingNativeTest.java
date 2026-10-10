package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsScalarRngMendingNativeTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private boolean originalJeb, originalMeek, originalStaring, originalPowerful;
    private int originalCookTime;

    @BeforeEach
    void preserve() {
        originalJeb = GeneralCompatConfig.jebSheepDropRandomColorWool;
        originalCookTime = GeneralCompatConfig.furnaceSmeltingTimeController;
        originalMeek = GeneralCompatConfig.meekEnderman;
        originalStaring = GeneralCompatConfig.staringEndermanNotAngry;
        originalPowerful = GeneralCompatConfig.powerfulExpMending;
        GeneralCompatConfig.jebSheepDropRandomColorWool = false;
        GeneralCompatConfig.furnaceSmeltingTimeController = -1;
        GeneralCompatConfig.meekEnderman = false;
        GeneralCompatConfig.staringEndermanNotAngry = false;
        GeneralCompatConfig.powerfulExpMending = false;
    }

    @AfterEach
    void reset() {
        GeneralCompatConfig.jebSheepDropRandomColorWool = originalJeb;
        GeneralCompatConfig.furnaceSmeltingTimeController = originalCookTime;
        GeneralCompatConfig.meekEnderman = originalMeek;
        GeneralCompatConfig.staringEndermanNotAngry = originalStaring;
        GeneralCompatConfig.powerfulExpMending = originalPowerful;
        ProbeSheep.originalCalls = 0;
        ProbeSheep.originalStack = null;
    }

    static class ProbeSheep extends Sheep {
        static int originalCalls;
        static ItemStack originalStack;

        ProbeSheep(Level world) {
            super(net.minecraft.world.entity.EntityTypes.SHEEP, world);
        }

        @Override
        protected void dropFromShearingLootTable(ServerLevel world, ResourceKey<LootTable> key, ItemInstance tool, BiConsumer<ServerLevel, ItemStack> consumer) {
            originalCalls++;
            consumer.accept(world, originalStack);
        }
    }

    @Test
    void jebActualDropUsesOneFreshRandomAndRedrawsTheLoopLimit() {
        GeneralCompatConfig.jebSheepDropRandomColorWool = true;
        var sheep = mock(ProbeSheep.class, CALLS_REAL_METHODS);
        doReturn(true).when(sheep).hasCustomName();
        doReturn(Component.literal("jeb_")).when(sheep).getCustomName();
        try (var constructed = mockConstruction(java.util.Random.class, (random, context) -> {
            when(random.nextInt(3)).thenReturn(0, 2, 1);
            when(random.nextInt(16)).thenReturn(4, 9);
        }); var stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            assertEquals(1, context.arguments().size());
            when(stack.getItem()).thenReturn(((net.minecraft.world.level.ItemLike) context.arguments().getFirst()).asItem());
            when(stack.getCount()).thenReturn(1);
        })) {
            var actual = sheep.generateDefaultDrops(mock(ServerLevel.class), ItemStack.EMPTY);
            assertEquals(1, constructed.constructed().size());
            verify(constructed.constructed().getFirst(), times(3)).nextInt(3);
            verify(constructed.constructed().getFirst(), times(2)).nextInt(16);
            assertEquals(List.of(Blocks.WOOL.pick(DyeColor.values()[4]).asItem(), Blocks.WOOL.pick(DyeColor.values()[9]).asItem()), actual.stream().map(ItemStack::getItem).toList());
            assertEquals(List.of(1, 1), actual.stream().map(ItemStack::getCount).toList());
            assertEquals(stacks.constructed(), actual);
            assertEquals(0, ProbeSheep.originalCalls);
            verify(sheep, never()).getRandom();
        }
    }

    @Test
    void jebDisabledAndOtherNamesRetainOriginalLootAndCreateNoRandom() {
        var sheep = mock(ProbeSheep.class, CALLS_REAL_METHODS);
        doReturn(true).when(sheep).hasCustomName();
        doReturn(Component.literal("jeb_")).when(sheep).getCustomName();
        ProbeSheep.originalStack = mock(ItemStack.class);
        when(ProbeSheep.originalStack.getCount()).thenReturn(2);
        when(ProbeSheep.originalStack.copyWithCount(1)).thenAnswer(call -> mock(ItemStack.class));
        try (var constructed = mockConstruction(java.util.Random.class)) {
            assertEquals(2, sheep.generateDefaultDrops(mock(ServerLevel.class), ItemStack.EMPTY).size());
            GeneralCompatConfig.jebSheepDropRandomColorWool = true;
            doReturn(Component.literal("Jeb_")).when(sheep).getCustomName();
            assertEquals(2, sheep.generateDefaultDrops(mock(ServerLevel.class), ItemStack.EMPTY).size());
            assertEquals(2, ProbeSheep.originalCalls);
            assertTrue(constructed.constructed().isEmpty());
        }
    }

    static void field(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @SuppressWarnings("unchecked")
    static RecipeManager.CachedCheck<SingleRecipeInput, AbstractCookingRecipe> check(AbstractFurnaceBlockEntity furnace) throws Exception {
        var check = (RecipeManager.CachedCheck<SingleRecipeInput, AbstractCookingRecipe>) mock(RecipeManager.CachedCheck.class);
        field(furnace, AbstractFurnaceBlockEntity.class, "quickCheck", check);
        when(furnace.getItem(0)).thenReturn(ItemStack.EMPTY);
        return check;
    }

    @Test
    void furnaceEnabledStillReadsInputQueriesRecipeAndComputesItsOriginalScalar() throws Exception {
        GeneralCompatConfig.furnaceSmeltingTimeController = 7;
        var furnace = mock(AbstractFurnaceBlockEntity.class);
        var level = mock(ServerLevel.class);
        var order = new ArrayList<String>();
        var check = check(furnace);
        var recipe = mock(AbstractCookingRecipe.class);
        when(furnace.getItem(0)).thenAnswer(call -> {
            order.add("input");
            return ItemStack.EMPTY;
        });
        when(check.getRecipeFor(any(), eq(level))).thenAnswer(call -> {
            order.add("query");
            return Optional.of(new RecipeHolder<>(null, recipe));
        });
        when(recipe.cookingTime()).thenAnswer(call -> {
            order.add("recipe");
            return 120;
        });
        field(furnace, AbstractFurnaceBlockEntity.class, "speedMultiplier", 0.5F);
        assertEquals(7, AbstractFurnaceBlockEntity.getTotalCookTime(level, furnace));
        assertEquals(List.of("input", "query", "recipe"), order);
    }

    @Test
    void furnaceEnabledNoRecipeStillQueriesBeforeReplacingFallback() throws Exception {
        GeneralCompatConfig.furnaceSmeltingTimeController = 0;
        var furnace = mock(AbstractFurnaceBlockEntity.class);
        var level = mock(ServerLevel.class);
        var check = check(furnace);
        when(check.getRecipeFor(any(), eq(level))).thenReturn(Optional.empty());
        assertEquals(0, AbstractFurnaceBlockEntity.getTotalCookTime(level, furnace));
        verify(check).getRecipeFor(any(), eq(level));
        verify(furnace).getItem(0);
    }

    @Test
    void furnaceDisabledRetainsRecipeSpeedAndNoRecipeFallback() throws Exception {
        var furnace = mock(AbstractFurnaceBlockEntity.class);
        var level = mock(ServerLevel.class);
        var check = check(furnace);
        var recipe = mock(AbstractCookingRecipe.class);
        when(recipe.cookingTime()).thenReturn(121);
        field(furnace, AbstractFurnaceBlockEntity.class, "speedMultiplier", 0.5F);
        when(check.getRecipeFor(any(), eq(level))).thenReturn(Optional.of(new RecipeHolder<>(null, recipe)), Optional.empty());
        assertEquals(242, AbstractFurnaceBlockEntity.getTotalCookTime(level, furnace));
        assertEquals(200, AbstractFurnaceBlockEntity.getTotalCookTime(level, furnace));
    }

    @Test
    void furnaceEnabledCannotSkipOriginalRecipeFailure() throws Exception {
        GeneralCompatConfig.furnaceSmeltingTimeController = 7;
        var furnace = mock(AbstractFurnaceBlockEntity.class);
        var level = mock(ServerLevel.class);
        var check = check(furnace);
        var failure = new IllegalStateException("actual recipe failed");
        when(check.getRecipeFor(any(), eq(level))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> AbstractFurnaceBlockEntity.getTotalCookTime(level, furnace)));
    }

    static boolean stare(Enderman entity, Player player) throws Exception {
        var method = Enderman.class.getDeclaredMethod("isBeingStaredBy", Player.class);
        method.setAccessible(true);
        return (boolean) method.invoke(entity, player);
    }

    @Test
    void meekEnabledStillCallsActualPaperEventBeforeReplacingItsTrueReturn() throws Exception {
        GeneralCompatConfig.meekEnderman = true;
        var entity = mock(Enderman.class);
        var player = mock(Player.class);
        try (var events = mockConstruction(com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true))) {
            assertFalse(stare(entity, player));
            assertEquals(1, events.constructed().size());
            var event = events.constructed().getFirst();
            var order = inOrder(event);
            order.verify(event).setCancelled(true);
            order.verify(event).callEvent();
        }
    }

    @Test
    void meekDisabledPreservesTheActualPaperEventReturn() throws Exception {
        var entity = mock(Enderman.class);
        var player = mock(Player.class);
        try (var events = mockConstruction(com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true, false))) {
            assertTrue(stare(entity, player));
            verify(events.constructed().getFirst()).callEvent();
        }
    }

    @SuppressWarnings("unchecked")
    static ItemStack stack(boolean mending, int damage) {
        var stack = mock(ItemStack.class);
        var enchantments = mock(ItemEnchantments.class);
        var key = (Holder<Enchantment>) mock(Holder.Reference.class);
        when(key.is(Enchantments.MENDING)).thenReturn(mending);
        when(enchantments.keySet()).thenReturn(Set.of(key));
        when(stack.getEnchantments()).thenReturn(enchantments);
        when(stack.getDamageValue()).thenReturn(damage);
        return stack;
    }

    static int repair(ExperienceOrb orb, ServerPlayer player, int amount) throws Exception {
        var method = ExperienceOrb.class.getDeclaredMethod("repairPlayerItems", ServerPlayer.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(orb, player, amount);
    }

    @Test
    void powerfulActualBodyUsesMendingKeyAllContainerSlotsAndNoPlayerRandomOrExtraEvent() throws Exception {
        GeneralCompatConfig.powerfulExpMending = true;
        var orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
        var player = mock(ServerPlayer.class);
        var inventory = mock(Inventory.class);
        var level = mock(ServerLevel.class);
        var eligible = stack(true, 3);
        var nonMending = stack(false, 10);
        var undamaged = stack(true, 0);
        when(player.getInventory()).thenReturn(inventory);
        when(player.level()).thenReturn(level);
        when(inventory.getContainerSize()).thenReturn(3);
        when(inventory.getItem(0)).thenReturn(nonMending);
        when(inventory.getItem(1)).thenReturn(eligible);
        when(inventory.getItem(2)).thenReturn(undamaged);
        try (var effects = mockStatic(EnchantmentHelper.class); var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            effects.when(() -> EnchantmentHelper.modifyDurabilityToRepairFromXp(eq(level), any(ItemStack.class), anyInt())).thenAnswer(call -> (int) call.getArgument(2) * 2);
            assertEquals(5, repair(orb, player, 6));
            verify(eligible).setDamageValue(0);
            effects.verify(() -> EnchantmentHelper.modifyDurabilityToRepairFromXp(eq(level), eq(nonMending), anyInt()), never());
            effects.verify(() -> EnchantmentHelper.modifyDurabilityToRepairFromXp(eq(level), eq(undamaged), anyInt()));
            events.verifyNoInteractions();
            verify(player, never()).getRandom();
            verify(player, never()).getBukkitEntity();
            verify(inventory, never()).getNonEquipmentItems();
            for (int slot = 0; slot < 3; slot++) verify(inventory).getItem(slot);
        }
    }

    @Test
    void powerfulMultipleCandidatesConsumeAllExperienceAndBreakAtZero() throws Exception {
        GeneralCompatConfig.powerfulExpMending = true;
        var orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
        var player = mock(ServerPlayer.class);
        var inventory = mock(Inventory.class);
        var level = mock(ServerLevel.class);
        var first = stack(true, 2);
        var second = stack(true, 2);
        when(player.getInventory()).thenReturn(inventory);
        when(player.level()).thenReturn(level);
        when(inventory.getContainerSize()).thenReturn(2);
        when(inventory.getItem(0)).thenReturn(first);
        when(inventory.getItem(1)).thenReturn(second);
        try (var effects = mockStatic(EnchantmentHelper.class)) {
            effects.when(() -> EnchantmentHelper.modifyDurabilityToRepairFromXp(eq(level), any(ItemStack.class), anyInt())).thenAnswer(call -> (int) call.getArgument(2) * 2);
            assertEquals(0, repair(orb, player, 1));
            assertEquals(1, mockingDetails(first).getInvocations().stream().filter(call -> call.getMethod().getName().equals("setDamageValue")).count() + mockingDetails(second).getInvocations().stream().filter(call -> call.getMethod().getName().equals("setDamageValue")).count());
        }
    }

    @Test
    void powerfulNegativeAmountStillScansItsContainerAndClampsTheActualReturn() throws Exception {
        GeneralCompatConfig.powerfulExpMending = true;
        var orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
        var player = mock(ServerPlayer.class);
        var inventory = mock(Inventory.class);
        var stack = stack(true, 3);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContainerSize()).thenReturn(1);
        when(inventory.getItem(0)).thenReturn(stack);
        try (var effects = mockStatic(EnchantmentHelper.class)) {
            assertEquals(0, repair(orb, player, -1));
            verify(inventory).getItem(0);
            effects.verifyNoInteractions();
            verify(stack, never()).setDamageValue(anyInt());
        }
    }
}
