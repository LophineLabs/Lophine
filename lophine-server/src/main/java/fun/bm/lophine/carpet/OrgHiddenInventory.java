// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.ArrayList;
import java.util.function.Predicate;

final class OrgHiddenInventory {
    final ServerPlayer player;

    OrgHiddenInventory(ServerPlayer player) {
        this.player = player;
    }

    ItemStack getItem(int slot) {
        return slot == 36 ? player.getOffhandItem() : player.getInventory().getNonEquipmentItems().get(slot);
    }

    void setItem(int slot, ItemStack stack) {
        if (slot == 36) player.setItemInHand(InteractionHand.OFF_HAND, stack);
        else player.getInventory().getNonEquipmentItems().set(slot, stack);
        player.getInventory().setChanged();
    }

    int size() {
        return 37;
    }

    static boolean fragile(ItemStack stack) {
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 10
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP);
    }

    boolean replenish(Predicate<ItemStack> predicate) {
        return replenish(InteractionHand.MAIN_HAND, predicate);
    }

    boolean replenish(InteractionHand hand, Predicate<ItemStack> predicate) {
        ItemStack held = player.getItemInHand(hand);
        if (predicate.test(held)) return true;
        int current = hand == InteractionHand.OFF_HAND ? 36 : player.getInventory().getSelectedSlot();
        var boxes = new ArrayList<ItemStack>();
        for (int slot = 0; slot < size(); slot++) {
            if (slot == current) continue;
            ItemStack candidate = getItem(slot);
            if (predicate.test(candidate)) {
                setItem(slot, held);
                player.setItemInHand(hand, candidate);
                return true;
            }
            if (GeneralCompatConfig.fakePlayerShulkerBoxItemHandling && OrgGameplayHelper.isShulkerBox(candidate))
                boxes.add(candidate);
        }
        for (ItemStack box : boxes) {
            ItemStack picked = pick(box, predicate, Integer.MAX_VALUE);
            if (picked.isEmpty()) continue;
            player.setItemInHand(hand, picked);
            OrgFakePlayerInventory.insert((ServerBot) player, held);
            return true;
        }
        return false;
    }

    boolean replenish(int threshold) {
        return replenish(InteractionHand.MAIN_HAND, threshold);
    }

    boolean replenish(InteractionHand hand, int threshold) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getCount() > threshold || held.getCount() == held.getMaxStackSize()) return true;
        var boxes = new ArrayList<ItemStack>();
        for (int slot = 0; slot < size(); slot++) {
            ItemStack candidate = getItem(slot);
            if (candidate == held || candidate.isEmpty()) continue;
            if (!held.isEmpty() && ItemStack.isSameItemSameComponents(candidate, held)) {
                int moved = Math.min(candidate.getCount(), held.getMaxStackSize() - held.getCount());
                candidate.shrink(moved);
                held.grow(moved);
                player.getInventory().setChanged();
                return true;
            }
            if (GeneralCompatConfig.fakePlayerShulkerBoxItemHandling && OrgGameplayHelper.isShulkerBox(candidate))
                boxes.add(candidate);
        }
        for (ItemStack box : boxes) {
            var predicate = (Predicate<ItemStack>) (stack -> !held.isEmpty() && ItemStack.isSameItemSameComponents(stack, held));
            int maximum = held.getMaxStackSize() - held.getCount();
            ItemStack picked;
            if (box.getCount() == 1) picked = pick(box, predicate, maximum);
            else {
                if (player.getInventory().getFreeSlot() == -1
                        || !box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().anyMatch(predicate))
                    continue;
                ItemStack split = box.split(1);
                picked = pick(split, predicate, maximum);
                player.getInventory().add(split);
                if (!split.isEmpty()) player.drop(split, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            if (picked.isEmpty()) continue;
            held.grow(picked.getCount());
            player.getInventory().setChanged();
            return true;
        }
        return false;
    }

    static ItemStack pick(ItemStack box, Predicate<ItemStack> accept, int maximum) {
        if (box.getCount() != 1) return ItemStack.EMPTY;
        var contents = new ArrayList<>(box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
        for (int index = 0; index < contents.size(); index++) {
            ItemStack stack = contents.get(index);
            if (stack.isEmpty() || !accept.test(stack)) continue;
            ItemStack picked = stack.split(Math.min(stack.getCount(), maximum));
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            return picked;
        }
        return ItemStack.EMPTY;
    }

    boolean contains(Predicate<ItemStack> accept) {
        for (int slot = 0; slot < size(); slot++) {
            ItemStack stack = getItem(slot);
            if (!stack.isEmpty() && accept.test(stack)) return true;
            if (GeneralCompatConfig.fakePlayerShulkerBoxItemHandling && stack.getCount() == 1 && OrgGameplayHelper.isShulkerBox(stack)
                    && stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().anyMatch(accept))
                return true;
        }
        return false;
    }

    void switchToAppropriateTool(Level world, BlockPos position) {
        var state = world.getBlockState(position);
        if (replenish(stack -> player.isCreative() ? stack.getItem().canDestroyBlock(player.getMainHandItem(), state, world, position, player)
                : !fragile(stack) && stack.getDestroySpeed(state) > 1)) return;
        replenish(stack -> !fragile(stack));
    }
}
