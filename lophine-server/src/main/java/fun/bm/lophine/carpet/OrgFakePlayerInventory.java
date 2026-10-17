package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * Actual item transfers for Org's fake-player storage and automatic restocking.
 */
public final class OrgFakePlayerInventory {
    private OrgFakePlayerInventory() {
    }

    private static boolean fragile(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 10
                && (FakePlayerCompatConfig.fakePlayerAutoReplaceTool || EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP));
    }

    private static boolean replenishEnabled() {
        return GeneralCompatConfig.fakePlayerAutoRestock || FakePlayerCompatConfig.fakePlayerAutoReplenishment
                || FakePlayerCompatConfig.fakePlayerAutoReplenishmentFormShulkerBox;
    }

    private static boolean shulkerEnabled() {
        return GeneralCompatConfig.fakePlayerShulkerBoxItemHandling || FakePlayerCompatConfig.fakePlayerAutoReplenishmentFormShulkerBox;
    }

    public static boolean shouldKeepInventory(CarpetFakePlayer bot, DamageSource direct) {
        if (!GeneralCompatConfig.fakePlayerKeepInventory) return false;
        if ("unconditional".equals(GeneralCompatConfig.fakePlayerKeepInventoryCondition)) return true;
        return direct != null && (direct.getDirectEntity() instanceof Player || direct.getEntity() instanceof Player
                || bot.getKillCredit() instanceof Player || direct.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
    }

    public static boolean restock(Player player, ItemStack before, ItemStack after, InteractionHand hand) {
        if (OrgGameplayHelper.insideOrgAction() || !(player instanceof CarpetFakePlayer bot) || hand == null)
            return false;
        boolean tools = GeneralCompatConfig.fakePlayerAutoRestock || FakePlayerCompatConfig.fakePlayerAutoReplaceTool;
        boolean replenish = replenishEnabled();
        if (!tools && !replenish) return false;
        if (tools && fragile(after)) {
            if (!replace(bot, hand, stack -> stack.is(after.getItem()) && !fragile(stack))
                    && FakePlayerCompatConfig.fakePlayerAutoReplaceTool) {
                int free = bot.getInventory().getFreeSlot();
                if (free >= 0) {
                    stored(bot, free, after);
                    bot.setItemInHand(hand, ItemStack.EMPTY);
                }
            }
        } else if (replenish && after.isEmpty() && !before.isEmpty()) {
            replace(bot, hand, stack -> ItemStack.isSameItemSameComponents(before, stack));
        } else if (replenish && !after.isEmpty() && after.getCount() <= Math.max(1, after.getMaxStackSize() / 2) && after.getCount() < after.getMaxStackSize()) {
            for (int i = 0; i <= 36; i++) {
                ItemStack source = stored(bot, i);
                if (source == after || source.isEmpty()) continue;
                if (ItemStack.isSameItemSameComponents(source, after)) {
                    int moved = Math.min(source.getCount(), after.getMaxStackSize() - after.getCount());
                    source.shrink(moved);
                    after.grow(moved);
                    bot.getInventory().setChanged();
                    return true;
                }
            }
            if (shulkerEnabled()) {
                for (int i = 0; i <= 36; i++) {
                    ItemStack source = stored(bot, i);
                    if (source == after || !OrgGameplayHelper.isShulkerBox(source)) continue;
                    ItemStack picked = pickFromBox(bot, source, stack -> ItemStack.isSameItemSameComponents(stack, after), after.getMaxStackSize() - after.getCount());
                    if (!picked.isEmpty()) {
                        after.grow(picked.getCount());
                        bot.getInventory().setChanged();
                        return true;
                    }
                }
            }
        }
        return true;
    }

    public static void broken(Player player, ItemStack before, EquipmentSlot slot) {
        if (OrgGameplayHelper.insideOrgAction() || !(player instanceof CarpetFakePlayer bot)
                || !(GeneralCompatConfig.fakePlayerAutoRestock || FakePlayerCompatConfig.fakePlayerAutoReplaceTool))
            return;
        InteractionHand hand = slot == EquipmentSlot.MAINHAND ? InteractionHand.MAIN_HAND : slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : null;
        if (hand != null) replace(bot, hand, stack -> stack.is(before.getItem()) && !fragile(stack));
    }

    private static ItemStack stored(CarpetFakePlayer bot, int slot) {
        return slot == 36 ? bot.getOffhandItem() : bot.getInventory().getNonEquipmentItems().get(slot);
    }

    private static void stored(CarpetFakePlayer bot, int slot, ItemStack stack) {
        if (slot == 36) bot.setItemInHand(InteractionHand.OFF_HAND, stack);
        else bot.getInventory().getNonEquipmentItems().set(slot, stack);
        bot.getInventory().setChanged();
    }

    private static boolean replace(CarpetFakePlayer bot, InteractionHand hand, Predicate<ItemStack> accept) {
        ItemStack current = bot.getItemInHand(hand);
        if (!current.isEmpty() && accept.test(current)) return true;
        for (int i = 0; i <= 36; i++) {
            ItemStack candidate = stored(bot, i);
            if (candidate == current || candidate.isEmpty() || !accept.test(candidate)) continue;
            stored(bot, i, current);
            bot.setItemInHand(hand, candidate);
            return true;
        }
        if (shulkerEnabled()) {
            for (int i = 0; i <= 36; i++) {
                ItemStack candidate = stored(bot, i);
                if (candidate == current || !OrgGameplayHelper.isShulkerBox(candidate) || candidate.getCount() != 1)
                    continue;
                ItemStack picked = pickFromBox(bot, candidate, accept, Integer.MAX_VALUE);
                if (picked.isEmpty()) continue;
                bot.setItemInHand(hand, picked);
                insert(bot, current);
                return true;
            }
        }
        return false;
    }

    private static ItemStack pickFromBox(CarpetFakePlayer bot, ItemStack source, Predicate<ItemStack> accept, int maximum) {
        ArrayList<ItemStack> contents = new ArrayList<>(source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
        for (ItemStack contained : contents) {
            if (contained.isEmpty() || !accept.test(contained)) continue;
            if (source.getCount() > 1 && bot.getInventory().getFreeSlot() < 0) return ItemStack.EMPTY;
            ItemStack picked = contained.split(Math.min(maximum, contained.getCount()));
            ItemStack edited = source.getCount() == 1 ? source : source.split(1);
            edited.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            if (edited != source) {
                bot.getInventory().add(edited);
                if (!edited.isEmpty()) bot.drop(edited, false, Prediction.SERVER_ONLY);
            }
            bot.getInventory().setChanged();
            return picked;
        }
        return ItemStack.EMPTY;
    }

    public static void insert(CarpetFakePlayer bot, ItemStack stack) {
        if (stack.isEmpty()) return;
        bot.getInventory().add(stack);
        if (stack.isEmpty()) return;
        ItemStack offhand = bot.getOffhandItem();
        if (offhand.isEmpty()) bot.setItemInHand(InteractionHand.OFF_HAND, stack.copyAndClear());
        else if (ItemStack.isSameItemSameComponents(offhand, stack)) {
            int moved = Math.min(stack.getCount(), Math.max(0, offhand.getMaxStackSize() - offhand.getCount()));
            offhand.grow(moved);
            stack.shrink(moved);
        }
        if (!stack.isEmpty() && GeneralCompatConfig.fakePlayerShulkerBoxItemHandling && stack.getItem().canFitInsideContainerItems()) {
            int lastShulker = -1;
            for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
                for (int i = 0; i <= 36 && !stack.isEmpty(); i++) {
                    ItemStack box = stored(bot, i);
                    if (!OrgGameplayHelper.isShulkerBox(box)) continue;
                    lastShulker = i;
                    if (box.getCount() != 1) continue;
                    var contained = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
                    boolean empty = contained.isEmpty();
                    boolean uniform = !empty && contained.stream().allMatch(item -> ItemStack.isSameItemSameComponents(item, stack));
                    boolean junk = contained.size() > 1 && contained.stream().anyMatch(item -> !ItemStack.isSameItemSameComponents(item, contained.getFirst()));
                    if (pass == 0 ? uniform || junk : empty) depositBox(box, stack);
                }
            }
            if (!stack.isEmpty() && lastShulker >= 0 && lastShulker < 36 && stored(bot, lastShulker + 1).isEmpty()) {
                ItemStack boxes = stored(bot, lastShulker);
                if (boxes.getCount() > 1) {
                    ItemStack separated = boxes.split(1);
                    stored(bot, lastShulker + 1, separated);
                    depositBox(separated, stack);
                }
            }
        }
        if (!stack.isEmpty()) bot.drop(stack.copyAndClear(), false, Prediction.SERVER_ONLY);
        bot.getInventory().setChanged();
    }

    private static void depositBox(ItemStack box, ItemStack stack) {
        ArrayList<ItemStack> contents = new ArrayList<>(box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
        int slots = GeneralCompatConfig.largeShulkerBox || contents.size() > 27 ? 54 : 27;
        while (contents.size() < slots) contents.add(ItemStack.EMPTY);
        for (int i = 0; i < slots && !stack.isEmpty(); i++) {
            ItemStack target = contents.get(i);
            if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) continue;
            int moved = Math.min(stack.getCount(), Math.max(0, target.getMaxStackSize() - target.getCount()));
            target.grow(moved);
            stack.shrink(moved);
        }
        for (int i = 0; i < slots && !stack.isEmpty(); i++) {
            if (!contents.get(i).isEmpty()) continue;
            contents.set(i, stack.split(stack.getMaxStackSize()));
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
    }
}
