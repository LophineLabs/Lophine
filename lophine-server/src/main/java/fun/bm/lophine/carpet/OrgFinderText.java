// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 (26.3-snapshot-9 -> 26.3).
package fun.bm.lophine.carpet;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Source predicate labels, counts and progress built from detached query state.
 */
final class OrgFinderText {
    static final String KEY = "carpet-org-addition.command.finder.";

    private OrgFinderText() {
    }

    static MutableComponent localized(String key, Object... values) {
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, key), values);
    }

    static MutableComponent finder(String suffix, Object... values) {
        return localized(KEY + suffix, values);
    }

    static String argument(com.mojang.brigadier.context.CommandContext<?> context, String name) {
        return context.getNodes().stream().filter(node -> node.getNode().getName().equals(name)).map(node -> node.getRange().get(context.getInput())).findFirst().orElse("");
    }

    static Optional<Item> item(String input) {
        if (input.startsWith("#") || input.startsWith("*") || input.contains("[")) return Optional.empty();
        Identifier id = Identifier.tryParse(input);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    static Component itemName(Item item) {
        return item.components().getOrDefault(DataComponents.ITEM_NAME, Component.empty());
    }

    static Component itemLabel(String input, Predicate<ItemStack> predicate) {
        if (predicate != null && predicate.test(ItemStack.EMPTY)) return itemName(Items.AIR);
        if (input.equals("*") || input.equals("*[]")) return localized("carpet-org-addition.item.any_item");
        return item(input).map(OrgFinderText::itemName).orElseGet(() -> abbreviate(input));
    }

    static Component blockLabel(String input) {
        Identifier id = Identifier.tryParse(input);
        if (id != null) {
            var block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isPresent()) return block.get().getName();
        }
        return abbreviate(input);
    }

    private static Component abbreviate(String input) {
        return input.length() > 30 ? Component.literal(input.substring(0, 27) + "...").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(input)))) : Component.literal(input);
    }

    static Component units(long count, int maximum) {
        long groups = count / maximum, remainder = count % maximum;
        return groups == 0 ? localized("carpet-org-addition.item.remainder", remainder) : remainder == 0 ? localized("carpet-org-addition.item.group", groups) : localized("carpet-org-addition.item.count", groups, remainder);
    }

    static Component total(String input, long count, boolean nested) {
        MutableComponent text = Component.literal(Long.toString(count));
        item(input).ifPresent(item -> text.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(units(count, item.getDefaultMaxStackSize())))));
        return nested ? text.withStyle(ChatFormatting.ITALIC) : text;
    }

    static Component count(OrgFinderStatistics.Items statistics) {
        MutableComponent hover = Component.empty();
        boolean first = true;
        for (var entry : statistics.counts().entrySet()) {
            if (!first) hover.append("\n");
            first = false;
            MutableComponent line = Component.empty().append(itemName(entry.getKey())).append(" ").append(units(entry.getValue(), entry.getKey().getDefaultMaxStackSize()));
            if (statistics.nested().contains(entry.getKey())) line.withStyle(ChatFormatting.ITALIC);
            hover.append(line);
        }
        MutableComponent value = Component.literal(Long.toString(statistics.total())).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
        return statistics.nested().isEmpty() ? value : value.withStyle(ChatFormatting.ITALIC);
    }

    static String decimals(double value) {
        return new java.text.DecimalFormat("#.##").format(value);
    }

    static Component progress(long completed, long total) {
        return Component.literal(decimals(total == 0 ? 100 : Math.min(1D, (double) completed / total) * 100) + "%");
    }

    static Component waiting() {
        String command = OrgCommandSettings.rewriteCommand("/finder stop");
        Component button = localized("carpet-org-addition.button.here").withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(localized("carpet-org-addition.button.run_command", command))));
        return finder("waiting_to_be_completed", button);
    }

    /**
     * Bounded exact piecewise inversion replaces the source's potentially unbounded mock-player loop.
     */
    static Component experienceLevel(BigInteger points) {
        if (points.signum() < 0) throw new IllegalArgumentException("Experience amount must not be negative");
        int low = 0, high = OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (OrgExperienceAmounts.forLevel(middle).compareTo(points) <= 0) low = middle;
            else high = middle - 1;
        }
        long needed = low >= 30 ? 112L + (low - 30L) * 9 : low >= 15 ? 37L + (low - 15L) * 5 : 7L + low * 2L;
        double partial = low == OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL ? 0 : points.subtract(OrgExperienceAmounts.forLevel(low)).doubleValue() / needed;
        return Component.literal(decimals(low + partial)).withStyle(style -> style.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal(points.toString()))));
    }
}
