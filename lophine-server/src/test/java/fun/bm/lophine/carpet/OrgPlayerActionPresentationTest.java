package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgPlayerActionPresentationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
        for (var item : List.of(Items.AIR, Items.CRAFTING_TABLE, Items.STONECUTTER, Items.ANVIL))
            try {
                item.builtInRegistryHolder().components();
            } catch (NullPointerException unbound) {
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).set(DataComponents.ITEM_NAME, Component.literal(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath())).build());
            }
    }

    private static String key(Component text) {
        return ((TranslatableContents) text.getContents()).getKey();
    }

    @Test
    void savedProfileNamesFollowAllSourceActionKeysAndCraftOverridesWithoutActors() {
        var expected = Map.ofEntries(Map.entry("stop", "stop"), Map.entry("categorize", "sorting"), Map.entry("empty_the_container", "clean"), Map.entry("fill_the_container", "fill"), Map.entry("crafting_table_craft", "craft.crafting_table"), Map.entry("inventory_craft", "craft.inventory"), Map.entry("rename", "rename"), Map.entry("stonecutting", "stonecutting"), Map.entry("trade", "trade"), Map.entry("fishing", "fishing"), Map.entry("plant", "plant"), Map.entry("bedrock", "bedrock"), Map.entry("goto", "stop"), Map.entry("librarian", "librarian"), Map.entry("enchanting", "enchanting"));
        try (var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS)) {
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            for (var entry : expected.entrySet()) {
                JsonObject profile = new JsonObject();
                profile.add(entry.getKey(), new JsonObject());
                assertEquals("carpet-org-addition.command.playerAction." + entry.getValue(), key(OrgPlayerActionPresentation.profileName(profile)), entry.getKey());
            }
            JsonObject profile = new JsonObject();
            profile.add("fill_the_container", new JsonObject());
            profile.add("categorize", new JsonObject());
            assertTrue(key(OrgPlayerActionPresentation.profileName(profile)).endsWith(".sorting"));
            profile.add("stop", new JsonObject());
            assertTrue(key(OrgPlayerActionPresentation.profileName(profile)).endsWith(".stop"));
            profile = new JsonObject();
            profile.addProperty("name", "craft_inventory");
            assertTrue(key(OrgPlayerActionPresentation.profileName(profile)).endsWith(".craft.inventory"));
        }
    }

    @Test
    void savedHiddenProfilesResolveSourceDisabledAndUnknownRegionsToStop() {
        try (var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS)) {
            JsonObject profile = new JsonObject();
            profile.add("plant", new JsonObject());
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(false);
            assertTrue(key(OrgPlayerActionPresentation.profileName(profile)).endsWith(".stop"));
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            JsonObject data = new JsonObject();
            data.addProperty("region_type", "future");
            profile = new JsonObject();
            profile.add("bedrock", data);
            assertTrue(key(OrgPlayerActionPresentation.profileName(profile)).endsWith(".stop"));
            assertTrue(key(OrgPlayerActionPresentation.profileName(new JsonObject())).endsWith(".stop"));
            assertTrue(key(OrgPlayerActionPresentation.profileName(null)).endsWith(".stop"));
        }
    }

    private static net.minecraft.commands.CommandBuildContext context() {
        var enchantments = new net.minecraft.core.MappedRegistry<Enchantment>(net.minecraft.core.registries.Registries.ENCHANTMENT, com.mojang.serialization.Lifecycle.stable());
        enchantments.freeze();
        var lookup = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.concat(net.minecraft.core.registries.BuiltInRegistries.REGISTRY.stream().map(registry -> (net.minecraft.core.HolderLookup.RegistryLookup<?>) registry), java.util.stream.Stream.of(enchantments)));
        return net.minecraft.commands.CommandBuildContext.simple(lookup, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
    }

    private static void pose(ServerPlayer player) {
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.getDisplayName()).thenReturn(Component.literal("fake"));
    }

    private static void slots(AbstractContainerMenu menu, ItemStack... values) {
        for (int i = 0; i < values.length; i++) {
            var slot = mock(Slot.class);
            when(slot.getItem()).thenReturn(values[i]);
            when(menu.getSlot(i)).thenReturn(slot);
        }
    }

    private static OrgFakePlayerActions.Action action(String kind, net.minecraft.core.Holder<Enchantment> enchantment) {
        var filters = List.of(OrgFakePlayerActions.ANY);
        if (kind.startsWith("craft_"))
            filters = java.util.Collections.nCopies(kind.equals("craft_table") ? 9 : 4, OrgFakePlayerActions.ANY);
        return new OrgFakePlayerActions.Action(kind, filters, 0, "new name", enchantment, false, false, false, new Vec3(1.25, 2.5, -3.75), new Vec3(-4.5, 5.25, 6.75), new OrgFakePlayerActions.Librarian(BlockPos.ZERO, 3, 11));
    }

    private static void pump(OrgInventoryPersistenceTest.Fixture f, CompletableFuture<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!result.isDone() && System.nanoTime() < deadline) {
            f.drain(f.viewer);
            f.drain(f.target);
            Thread.sleep(1);
        }
        assertTrue(result.isDone());
        result.join();
    }

    @Test
    void everyActualPublicInfoCommandEmitsSourceDetailedLinesAndWaitsLateNativeFeedback() throws Exception {
        for (String kind : List.of("stop", "fishing", "empty", "fill", "sorting", "craft_inventory", "craft_table", "trade", "rename", "stonecutting", "enchanting", "librarian"))
            try (var f = new OrgInventoryPersistenceTest.Fixture(directory.resolve(kind), true); var scope = CarpetAsyncCommandResults.open()) {
                var bot = f.target.player();
                pose(bot);
                pose(f.viewer.player());
                f.owner.set(bot);
                var enchantment = mock(Enchantment.class);
                when(enchantment.description()).thenReturn(Component.literal("Mending"));
                when(enchantment.getMaxLevel()).thenReturn(3);
                OrgFakePlayerActions.set(bot, action(kind, net.minecraft.core.Holder.direct(enchantment)));
                if (kind.equals("craft_inventory"))
                    slots(bot.inventoryMenu, ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 2), ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.EMERALD, 3));
                when(f.server.getPlayerList().getPlayerByName("fake")).thenReturn(bot);
                var dispatcher = new CommandDispatcher<CommandSourceStack>();
                OrgFakePlayerActionCommands.register(dispatcher, context());
                var source = mock(CommandSourceStack.class);
                when(source.getServer()).thenReturn(f.server);
                when(source.getEntity()).thenReturn(f.viewer.player());
                when(source.getPlayer()).thenReturn(f.viewer.player());
                when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
                when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
                var child = new CompletableFuture<Void>();
                var late = new CompletableFuture<Void>();
                var lines = new ArrayList<Component>();
                doAnswer(call -> {
                    assertSame(f.viewer.player(), f.owner.get());
                    lines.add(call.getArgument(0));
                    ScarpetNativeWork.record(child);
                    if (lines.size() == 1)
                        child.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> ScarpetNativeWork.record(late)));
                    return null;
                }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
                f.owner.set(null);
                var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> {
                    try {
                        return dispatcher.execute("playerAction fake info", source);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                var result = scope.resultFuture(source);
                assertNotNull(result);
                f.drain(f.target);
                f.drain(f.viewer);
                assertFalse(lines.isEmpty());
                String leaf = kind.equals("empty") ? "clean" : kind.startsWith("craft_") ? "craft" : kind;
                assertEquals("carpet-org-addition.command.playerAction." + leaf + ".info", key(lines.getFirst()));
                assertFalse(result.isDone());
                assertFalse(parent.isDone());
                int count = switch (kind) {
                    case "sorting", "rename" -> 3;
                    case "craft_inventory" -> 6;
                    case "craft_table" -> 5;
                    case "trade", "stonecutting", "enchanting" -> 2;
                    case "librarian" -> 4;
                    default -> 1;
                };
                assertEquals(count, lines.size(), kind);
                if (kind.equals("librarian")) {
                    assertTrue(key(lines.get(2)).endsWith(".max_level"));
                    assertTrue(key(lines.get(3)).endsWith(".min_price"));
                    assertInstanceOf(HoverEvent.ShowText.class, lines.get(2).getStyle().getHoverEvent());
                    assertInstanceOf(HoverEvent.ShowText.class, lines.get(3).getStyle().getHoverEvent());
                }
                if (kind.equals("sorting")) {
                    var args = ((TranslatableContents) lines.get(1).getContents()).getArgs();
                    assertEquals("1.25 2.50 -3.75", ((Component) args[1]).getString());
                }
                child.complete(null);
                assertFalse(result.isDone());
                late.complete(null);
                pump(f, result);
                assertEquals(1, result.join());
                parent.get(3, TimeUnit.SECONDS);
                f.owner.set(bot);
                OrgFakePlayerActions.set(bot, OrgFakePlayerActions.Action.simple("stop", List.of()));
            }
    }

    @Test
    void liveTradeAvailableAndDisabledAndAnvilStatesKeepSourceSlotsAndCountHover() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            pose(bot);
            f.owner.set(bot);
            var offer = mock(net.minecraft.world.item.trading.MerchantOffer.class);
            when(offer.getCostA()).thenReturn(new ItemStack(Items.EMERALD, 7));
            when(offer.getCostB()).thenReturn(ItemStack.EMPTY);
            when(offer.getResult()).thenReturn(new ItemStack(Items.DIAMOND, 2));
            var offers = new net.minecraft.world.item.trading.MerchantOffers();
            offers.add(offer);
            var menu = mock(MerchantMenu.class);
            when(menu.getOffers()).thenReturn(offers);
            slots(menu, new ItemStack(Items.EMERALD, 9), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 2));
            bot.containerMenu = menu;
            var info = OrgPlayerActionInfo.info(bot, action("trade", null));
            assertEquals(4, info.size());
            assertTrue(key(info.get(2)).endsWith(".state"));
            var offered = info.get(1).getSiblings().getFirst();
            assertTrue(((HoverEvent.ShowText) offered.getStyle().getHoverEvent()).value().getString().endsWith("*7"));
            when(offer.isOutOfStock()).thenReturn(true);
            info = OrgPlayerActionInfo.info(bot, action("trade", null));
            assertEquals(3, info.size());
            assertTrue(key(info.get(2)).endsWith(".disabled"));
            var anvil = mock(AnvilMenu.class);
            slots(anvil, new ItemStack(Items.DIAMOND, 3), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 3));
            bot.containerMenu = anvil;
            bot.experienceLevel = 12;
            info = OrgPlayerActionInfo.info(bot, action("rename", null));
            assertEquals(3, info.size());
            assertEquals(12, ((TranslatableContents) info.get(1).getContents()).getArgs()[0]);
            assertFalse(info.get(2).getString().contains("OrgFakePlayerActions"));
        }
    }

    @Test
    void nativeCraftRecipeResultAndRealGridSlotsArePresentedWithSourceGlyphsAndHovers() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            pose(bot);
            f.owner.set(bot);
            var menu = mock(CraftingMenu.class);
            ItemStack[] values = new ItemStack[10];
            java.util.Arrays.fill(values, ItemStack.EMPTY);
            values[0] = new ItemStack(Items.DIAMOND, 2);
            values[5] = new ItemStack(Items.EMERALD, 3);
            slots(menu, values);
            bot.containerMenu = menu;
            var manager = mock(net.minecraft.world.item.crafting.RecipeManager.class);
            when(f.server.getRecipeManager()).thenReturn(manager);
            var recipe = mock(net.minecraft.world.item.crafting.CraftingRecipe.class);
            when(recipe.assemble(any(net.minecraft.world.item.crafting.CraftingInput.class))).thenReturn(new ItemStack(Items.DIAMOND, 2));
            var holder = mock(net.minecraft.world.item.crafting.RecipeHolder.class);
            when(holder.value()).thenReturn(recipe);
            var world = bot.level();
            doReturn(Optional.of(holder)).when(manager).getRecipeFor(eq(net.minecraft.world.item.crafting.RecipeType.CRAFTING), any(net.minecraft.world.item.crafting.CraftingInput.class), eq(world));
            var filter = new OrgFakePlayerActions.Filter("minecraft:emerald", stack -> stack.is(Items.EMERALD));
            var action = OrgFakePlayerActions.Action.simple("craft_table", java.util.Collections.nCopies(9, filter));
            var info = OrgPlayerActionInfo.info(bot, action);
            assertEquals(8, info.size());
            assertEquals("    [E] [E] [E] -> [D]", info.get(2).getString());
            assertEquals("    [A] [E] [A] -> [D]", info.get(6).getString());
            assertInstanceOf(HoverEvent.ShowText.class, info.get(2).getSiblings().getLast().getStyle().getHoverEvent());
            verify(recipe).assemble(any(net.minecraft.world.item.crafting.CraftingInput.class));
        }
    }

    @Test
    void predicateFormattingPreservesWildcardsTagsLongExpressionsAndEmptyGlyph() {
        assertTrue(key(OrgPlayerActionInfo.filter(OrgFakePlayerActions.ANY)).endsWith(".item.any_item"));
        assertEquals("[#]", OrgPlayerActionInfo.initial(new OrgFakePlayerActions.Filter("#minecraft:logs", stack -> false)).getString());
        var text = "#minecraft:a_very_long_predicate_name_to_preserve";
        var abbreviated = OrgPlayerActionInfo.filter(new OrgFakePlayerActions.Filter(text, stack -> false));
        assertEquals(text.substring(0, 27) + "...", abbreviated.getString());
        assertTrue(abbreviated.getStyle().isItalic());
        assertEquals(text, ((HoverEvent.ShowText) abbreviated.getStyle().getHoverEvent()).value().getString());
        assertEquals("[A]", OrgPlayerActionInfo.stack(ItemStack.EMPTY).getString());
    }

    @Test
    void actualStoneRecipeAndNativeMenuButtonsAndInvalidRecipeKeepSourcePresentation() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            pose(bot);
            f.owner.set(bot);
            var world = bot.level();
            var access = mock(net.minecraft.world.item.crafting.RecipeManager.class, RETURNS_DEEP_STUBS);
            when(world.recipeAccess()).thenReturn(access);
            var recipes = access.stonecutterRecipes();
            var selected = recipes.selectByInput(new ItemStack(Items.EMERALD));
            var entry = mock(net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry.class, RETURNS_DEEP_STUBS);
            var selectable = entry.recipe();
            var recipe = mock(net.minecraft.world.item.crafting.StonecutterRecipe.class);
            when(recipe.assemble(any(net.minecraft.world.item.crafting.SingleRecipeInput.class))).thenReturn(new ItemStack(Items.DIAMOND));
            var holder = mock(net.minecraft.world.item.crafting.RecipeHolder.class);
            when(holder.value()).thenReturn(recipe);
            when(selectable.recipe()).thenReturn(Optional.of(holder));
            when(recipes.selectByInput(any(ItemStack.class))).thenReturn(selected);
            doReturn(List.of(entry)).when(selected).entries();
            var menu = mock(StonecutterMenu.class);
            slots(menu, new ItemStack(Items.EMERALD, 4), new ItemStack(Items.DIAMOND, 2));
            bot.containerMenu = menu;
            var filter = new OrgFakePlayerActions.Filter("minecraft:emerald", stack -> stack.is(Items.EMERALD));
            var action = OrgFakePlayerActions.Action.simple("stonecutting", List.of(filter));
            var info = OrgPlayerActionInfo.info(bot, action);
            assertEquals(3, info.size());
            assertTrue(key(info.get(1)).endsWith(".button"));
            assertEquals(1, ((TranslatableContents) info.get(1).getContents()).getArgs()[0]);
            assertEquals("    [E] -> [D]", info.get(2).getString());
            verify(recipe).assemble(any(net.minecraft.world.item.crafting.SingleRecipeInput.class));
            action = new OrgFakePlayerActions.Action("stonecutting", List.of(filter), 1, "", null, false, false, false, Vec3.ZERO, Vec3.ZERO);
            info = OrgPlayerActionInfo.info(bot, action);
            var invalid = (Component) ((TranslatableContents) info.getFirst().getContents()).getArgs()[3];
            assertEquals("Invalid", invalid.getString());
            assertTrue(invalid.getStyle().isObfuscated());
        }
    }

    @Test
    void actualSortingMultiplePredicatesExposeTheirSourceHoverWithoutRawActionRecords() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            pose(bot);
            f.owner.set(bot);
            var action = OrgFakePlayerActions.Action.simple("sorting", List.of(new OrgFakePlayerActions.Filter("#minecraft:logs", stack -> false), OrgFakePlayerActions.ANY));
            var info = OrgPlayerActionInfo.info(bot, action);
            assertEquals(3, info.size());
            var title = (Component) ((TranslatableContents) info.getFirst().getContents()).getArgs()[1];
            assertTrue(title.getStyle().isBold());
            assertTrue(title.getStyle().isItalic());
            var hover = ((HoverEvent.ShowText) title.getStyle().getHoverEvent()).value().getString();
            assertTrue(hover.contains("#minecraft:logs"));
            assertTrue(hover.contains("\n"));
            assertFalse(info.toString().contains("OrgFakePlayerActions.Action"));
        }
    }
}
