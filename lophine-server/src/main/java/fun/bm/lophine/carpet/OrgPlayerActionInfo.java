// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 action info implementations.
package fun.bm.lophine.carpet;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Source detailed action information is sampled on the real player's owner, then sent as immutable lines.
 */
final class OrgPlayerActionInfo {
    private static final String PREFIX = "carpet-org-addition.command.playerAction.";

    private OrgPlayerActionInfo() {
    }

    static List<Component> info(ServerPlayer player, OrgFakePlayerActions.Action action) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player, "Org action information requires its player owner");
        var lines = new ArrayList<Component>();
        Component name = player.getDisplayName();
        String kind = action.kind();
        String key = PREFIX + (kind.equals("empty") ? "clean" : kind.startsWith("craft_") ? "craft" : kind) + ".info";
        switch (kind) {
            case "stop", "fishing" -> lines.add(localized(key, "%s is " + kind, name));
            case "empty", "fill" ->
                    lines.add(localized(key, "%s is operating a container with %s", name, filter(action.filters().getFirst())));
            case "sorting" -> {
                Component selected;
                if (action.filters().size() == 1) selected = filter(action.filters().getFirst());
                else {
                    var hover = Component.empty();
                    for (int i = 0; i < action.filters().size(); i++) {
                        if (i > 0) hover.append("\n");
                        hover.append(filter(action.filters().get(i)));
                    }
                    selected = itemText().withStyle(style -> style.withBold(true).withItalic(true).withHoverEvent(new HoverEvent.ShowText(hover)));
                }
                lines.add(localized(key, "%s is sorting %s", name, selected));
                lines.add(localized(key + ".this", "%s is dropped at %s", selected, position(action.selected())));
                lines.add(localized(key + ".other", "Other items are dropped at %s", position(action.other())));
            }
            case "craft_inventory", "craft_table" -> craft(player, action, lines, key);
            case "trade" -> {
                lines.add(localized(key, "%s is trading option %s", name, action.index() + 1));
                if (player.containerMenu instanceof MerchantMenu menu) {
                    var offer = menu.getOffers().get(action.index());
                    lines.add(row(stack(offer.getCostA()), stack(offer.getCostB()), stack(offer.getResult())));
                    if (offer.isOutOfStock()) lines.add(localized(key + ".disabled", "Trading option is disabled"));
                    else {
                        lines.add(localized(key + ".state", "Trading status:"));
                        lines.add(row(stack(menu.getSlot(0).getItem()), stack(menu.getSlot(1).getItem()), stack(menu.getSlot(2).getItem())));
                    }
                } else lines.add(localized(key + ".no_villager", "%s is not trading", name));
            }
            case "rename" -> {
                lines.add(localized(key, "%s is renaming %s to %s", name, filter(action.filters().getFirst()), action.name()));
                lines.add(localized(key + ".xp", "Experience level: %s", player.experienceLevel));
                if (player.containerMenu instanceof AnvilMenu menu)
                    lines.add(row(stack(menu.getSlot(0).getItem()), stack(menu.getSlot(1).getItem()), stack(menu.getSlot(2).getItem())));
                else
                    lines.add(localized(key + ".no_anvil", "%s has not opened %s", name, net.minecraft.world.level.block.Blocks.ANVIL.getName()));
            }
            case "stonecutting" -> {
                lines.add(localized(key, "%s is crafting with %s and %s into %s", name, itemName(Items.STONECUTTER), filter(action.filters().getFirst()), stoneOutput(player, action)));
                if (player.containerMenu instanceof StonecutterMenu menu) {
                    lines.add(localized(key + ".button", "Button index: %s", action.index() + 1));
                    lines.add(row(stack(menu.getSlot(0).getItem()), null, stack(menu.getSlot(1).getItem())));
                } else
                    lines.add(localized(key + ".no_stonecutter", "%s has not opened %s", name, itemName(Items.STONECUTTER)));
            }
            case "enchanting" -> {
                lines.add(localized(key, "%s is enchanting %s on %s", name, action.enchantment().value().description(), filter(action.filters().getFirst())));
                lines.add(localized(key + ".xp", "Experience level: %s", player.experienceLevel));
            }
            case "librarian" -> {
                var enchantment = action.enchantment();
                int maximum = enchantment.value().getMaxLevel(), level = action.librarian().minLevel(), price = action.librarian().maxPrice();
                int factor = enchantment.is(EnchantmentTags.DOUBLE_TRADE_PRICE) ? 2 : 1, min = (2 + level * 3) * factor, max = (6 + level * 13) * factor;
                lines.add(localized(key, "%s is farming enchanted book trades", name));
                lines.add(localized(key + ".enchantment", "Enchantment: %s", enchantment.value().description()));
                lines.add(localized(key + (level == maximum ? ".max_level" : ".level"), "Minimum accepted level: %s", level).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(localized(key + ".level.prompt", "This enchantment's max level is %s", maximum)))));
                lines.add(localized(key + (price == min ? ".min_price" : ".price"), "Maximum accepted price: %s", price).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(localized(key + ".price.prompt", "Price range: [%s-%s] (at min accepted level %s)", min, max, level)))));
            }
            default -> throw new IllegalArgumentException("Unknown Org action information: " + kind);
        }
        return List.copyOf(lines);
    }

    private static void craft(ServerPlayer player, OrgFakePlayerActions.Action action, List<Component> lines, String key) {
        int width = action.kind().equals("craft_table") ? 3 : 2;
        ItemStack output = craftOutput(player, action, width);
        lines.add(localized(key, "%s is crafting %s:", player.getDisplayName(), output.isEmpty() ? itemText() : itemName(output.getItem())));
        for (int y = 0; y < width; y++) {
            var line = Component.literal("    ");
            for (int x = 0; x < width; x++) {
                if (x > 0) line.append(" ");
                line.append(initial(action.filters().get(y * width + x)));
            }
            if (y == 1 && !output.isEmpty()) line.append(" -> ").append(stack(output));
            lines.add(line);
        }
        AbstractContainerMenu menu = width == 2 ? player.inventoryMenu : player.containerMenu instanceof CraftingMenu ? player.containerMenu : null;
        if (menu == null) {
            lines.add(localized(key + ".no_crafting_table", "%s has not opened %s", player.getDisplayName(), itemName(Items.CRAFTING_TABLE)));
            return;
        }
        lines.add(localized(key + ".state", "Crafting status of %s:", player.getDisplayName()));
        for (int y = 0; y < width; y++) {
            var line = Component.literal("    ");
            for (int x = 0; x < width; x++) {
                if (x > 0) line.append(" ");
                line.append(stack(menu.getSlot(1 + y * width + x).getItem()));
            }
            if (y == 1) line.append(" -> ").append(stack(menu.getSlot(0).getItem()));
            lines.add(line);
        }
    }

    private static ItemStack craftOutput(ServerPlayer player, OrgFakePlayerActions.Action action, int width) {
        var input = new ArrayList<ItemStack>();
        for (var predicate : action.filters()) {
            var item = convert(predicate);
            if (item.isEmpty()) return ItemStack.EMPTY;
            input.add(item.get().getDefaultInstance());
        }
        var grid = CraftingInput.of(width, width, input);
        return player.level().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, player.level()).map(recipe -> recipe.value().assemble(grid)).orElse(ItemStack.EMPTY);
    }

    private static Component stoneOutput(ServerPlayer player, OrgFakePlayerActions.Action action) {
        var item = convert(action.filters().getFirst());
        if (item.isEmpty()) return itemText();
        var input = item.get().getDefaultInstance();
        var entries = player.level().recipeAccess().stonecutterRecipes().selectByInput(input).entries();
        if (action.index() >= entries.size()) return Component.literal("Invalid").withStyle(ChatFormatting.OBFUSCATED);
        var output = entries.get(action.index()).recipe().recipe().map(recipe -> recipe.value().assemble(new SingleRecipeInput(input))).orElse(ItemStack.EMPTY);
        return itemName(output.getItem());
    }

    private static Optional<Item> convert(OrgFakePlayerActions.Filter filter) {
        String text = filter.expression();
        if (text.startsWith("#") || text.startsWith("*") || text.contains("[")) return Optional.empty();
        Identifier id = Identifier.tryParse(text);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    static Component filter(OrgFakePlayerActions.Filter filter) {
        String text = filter.expression();
        if (text.equals("*")) return localized("carpet-org-addition.item.any_item", "any item");
        var item = convert(filter);
        if (item.isPresent()) return itemName(item.get());
        return text.length() > 30 ? Component.literal(text.substring(0, 27) + "...").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(text)))) : Component.literal(text);
    }

    static Component initial(OrgFakePlayerActions.Filter filter) {
        String text = filter.expression();
        if (text.equals("air") || text.equals("minecraft:air"))
            return Component.literal("[A]").withStyle(ChatFormatting.DARK_GRAY).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(itemName(Items.AIR))));
        String glyph = text.startsWith("#") ? "[#]" : text.startsWith("*") ? "[*]" : text.contains("[") ? "[@]" : "[" + Character.toUpperCase(text.substring(text.indexOf(':') + 1).charAt(0)) + "]";
        return Component.literal(glyph).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(convert(filter).map(OrgPlayerActionInfo::itemName).orElseGet(() -> Component.literal(text)))));
    }

    static Component stack(ItemStack stack) {
        if (stack.isEmpty())
            return Component.literal("[A]").withStyle(ChatFormatting.DARK_GRAY).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(itemName(Items.AIR))));
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return Component.literal("[" + Character.toUpperCase(path.charAt(0)) + "]")
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.empty().append(itemName(stack.getItem())).append("*" + stack.getCount()))));
    }

    private static MutableComponent row(Component first, Component second, Component output) {
        var line = Component.literal("    ").append(first);
        if (second != null) line.append(" ").append(second);
        return line.append(" -> ").append(output);
    }

    private static Component position(Vec3 value) {
        return Component.literal(String.format("%.2f %.2f %.2f", value.x, value.y, value.z));
    }

    private static Component itemName(Item item) {
        return item.components().getOrDefault(DataComponents.ITEM_NAME, Component.empty());
    }

    private static MutableComponent itemText() {
        return localized("carpet-org-addition.item.item", "item");
    }

    private static MutableComponent localized(String key, String fallback, Object... values) {
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, fallback), values);
    }
}
