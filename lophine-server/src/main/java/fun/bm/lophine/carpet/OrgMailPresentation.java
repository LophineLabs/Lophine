// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Parcel presentation reads immutable parcel copies and actual owner display-name snapshots.
 */
final class OrgMailPresentation {
    private OrgMailPresentation() {
    }

    static MutableComponent mail(String suffix, Object... arguments) {
        return text("carpet-org-addition.command.mail." + suffix, suffix, arguments);
    }

    static final class Failure extends IllegalArgumentException {
        final Component component;

        Failure(Component component) {
            super(component.getString());
            this.component = component;
        }
    }

    private static MutableComponent text(String key, String fallback, Object... arguments) {
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, fallback), arguments);
    }

    static Component name(Item item) {
        return item.components().getOrDefault(DataComponents.ITEM_NAME, Component.empty());
    }

    static Component display(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return name(Items.AIR).copy();
        ItemStack first = stacks.getFirst();
        boolean same = true;
        for (ItemStack stack : stacks)
            if (!ItemStack.isSameItemSameComponents(first, stack)) {
                same = false;
                if (!first.is(stack.getItem()))
                    return text("carpet-org-addition.item.item", "Item").withStyle(style -> style.withItalic(true).withHoverEvent(new HoverEvent.ShowText(itemList(stacks))));
            }
        Component result = name(first.getItem()).copy();
        return same ? result.copy().withStyle(style -> style.withColor(first.getRarity().color()).withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromStack(first)))) : result;
    }

    static Component itemList(List<ItemStack> stacks) {
        var counts = new LinkedHashMap<Item, Integer>();
        for (ItemStack stack : stacks)
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        var result = Component.empty();
        for (var entry : counts.entrySet()) {
            if (!result.getSiblings().isEmpty()) result.append("\n");
            result.append(Component.empty().append(name(entry.getKey())).append("*" + entry.getValue()));
        }
        return result;
    }

    static Component click(String command) {
        String rewritten = OrgCommandSettings.rewriteCommand(command);
        return text("carpet-org-addition.button.here", "[here]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(rewritten)).withHoverEvent(new HoverEvent.ShowText(text("carpet-org-addition.button.run_command", "Run command: %s", rewritten))));
    }

    static Component gray(Component value) {
        return value.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    static Component received(Component player, List<ItemStack> before, List<ItemStack> after) {
        var received = new java.util.ArrayList<ItemStack>();
        var remaining = new LinkedHashMap<Item, Integer>();
        for (ItemStack stack : after) remaining.merge(stack.getItem(), stack.getCount(), Integer::sum);
        var original = new LinkedHashMap<Item, Integer>();
        for (ItemStack stack : before) original.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (var entry : original.entrySet()) {
            int count = entry.getValue() - remaining.getOrDefault(entry.getKey(), 0);
            if (count > 0) received.add(new ItemStack(entry.getKey(), count));
        }
        return gray(mail("notice.collect", player)).copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(itemList(received))));
    }

    static Component line(int id, CompoundTag document, List<ItemStack> stacks, String operation) {
        var operationText = mail("list." + operation).withStyle(operation.equals("view") ? ChatFormatting.GRAY : ChatFormatting.AQUA);
        if (!operation.equals("view"))
            operationText.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(OrgCommandSettings.rewriteCommand("/mail " + operation + " " + id))));
        int[] time = document.getIntArray("time").orElse(new int[]{0, 0, 0, 0, 0, 0});
        Object[] values = new Object[6];
        for (int i = 0; i < 6; i++) values[i] = i < time.length ? time[i] : 0;
        Component display = display(stacks);
        int count = OrgMailService.count(stacks);
        var hover = Component.empty().append(mail("list.id", id)).append("\n").append(mail("list.sender", document.getStringOr("sender", ""))).append("\n").append(mail("list.recipient", document.getStringOr("recipient", ""))).append("\n").append(mail("list.item", display, count)).append("\n").append(mail("list.time", text("carpet-org-addition.time.format", "%s/%s/%s %s:%s:%s", values)));
        operationText.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
        return mail("list.each", id, display, count, operationText);
    }
}
