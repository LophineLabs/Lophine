package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.function.Predicate;

/**
 * Server-side quick shulker menus and right-click inventory transfers.
 */
public final class OrgQuickShulker {
    private OrgQuickShulker() {
    }

    public static boolean operable(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1 && OrgGameplayHelper.isShulkerBox(stack);
    }

    public static boolean isOpenedShulker(ServerPlayer player, ItemStack stack) {
        TickThread.ensureTickThread(player, "Quick shulker descriptor lookup must own its player");
        return player.containerMenu instanceof Menu menu && OrgItemShadowGroups.sameAlias(menu.source, stack);
    }

    public static boolean open(ServerPlayer player, ItemStack shulker, Slot sourceSlot) {
        if (OrgInventoryTransfers.isPreview() || !GeneralCompatConfig.quickShulker || !operable(shulker)) return false;
        AbstractContainerMenu previous = player.containerMenu;
        if (previous instanceof Menu menu && OrgItemShadowGroups.sameAlias(menu.source, shulker)) return false;
        var originLevel = player.level();
        var origin = player.blockPosition().immutable();
        Predicate<Player> accessible = sourceSlot == null
                ? current -> OrgItemShadowGroups.sameAlias(current.getMainHandItem(), shulker) || OrgItemShadowGroups.sameAlias(current.getOffhandItem(), shulker)
                : current -> current.level() == originLevel && TickThread.isTickThreadFor(originLevel, origin)
                && previous.stillValid(current) && OrgItemShadowGroups.sameAlias(sourceSlot.getItem(), shulker);
        if (!accessible.test(player)) return false;
        Backing inventory = new Backing(shulker, sourceSlot == null ? player.getInventory()::setChanged : sourceSlot::setChanged);
        return player.openMenu(new SimpleMenuProvider((id, playerInventory, ignored) -> new Menu(id, playerInventory, inventory, shulker, accessible), shulker.getHoverName())).isPresent();
    }

    private static void returnRemainder(Player player, ItemStack remaining) {
        if (remaining.isEmpty()) return;
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) player.drop(remaining, true, Prediction.SERVER_ONLY);
    }

    public static boolean stackedOnOther(ItemStack self, Slot slot, ClickAction action, Player player) {
        if (!GeneralCompatConfig.quickShulker || action != ClickAction.SECONDARY || !operable(self)) return false;
        Backing inventory = new Backing(self, player.getInventory()::setChanged);
        ItemStack slotStack = slot.getItem();
        if (slotStack.isEmpty()) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack first = inventory.getItem(i);
                if (first.isEmpty()) continue;
                if (slot.mayPlace(first)) {
                    ItemStack extracted = inventory.removeItemNoUpdate(i);
                    inventory.setChanged();
                    ItemStack remaining = slot.safeInsert(extracted);
                    if (!remaining.isEmpty()) returnRemainder(player, inventory.addItem(remaining));
                }
                break;
            }
        } else if (slotStack.getItem().canFitInsideContainerItems()) {
            int capacity = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack target = inventory.getItem(i);
                if (target.isEmpty()) capacity += slotStack.getMaxStackSize();
                else if (ItemStack.isSameItemSameComponents(target, slotStack))
                    capacity += Math.max(0, target.getMaxStackSize() - target.getCount());
            }
            ItemStack taken = slot.safeTake(slotStack.getCount(), capacity, player);
            returnRemainder(player, inventory.addItem(taken));
        }
        return true;
    }

    public static boolean otherStackedOnMe(ItemStack self, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess carried) {
        if (!GeneralCompatConfig.quickShulker || action != ClickAction.SECONDARY || !operable(self) || !slot.allowModification(player))
            return false;
        if (!other.isEmpty() && other.getItem().canFitInsideContainerItems()) {
            carried.set(new Backing(self, slot::setChanged).addItem(carried.get()));
        }
        return true;
    }

    private static final class Backing extends SimpleContainer {
        private final ItemStack source;
        private final Runnable changed;
        private java.util.List<ItemStack> overflow;
        private ItemContainerContents loadedContents;

        Backing(ItemStack source, Runnable changed) {
            super(GeneralCompatConfig.largeShulkerBox || source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).size() > 27 ? 54 : 27);
            this.source = source;
            this.changed = changed;
            this.reload();
        }

        private void reload() {
            this.loadedContents = this.source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            var contents = this.loadedContents.itemCopies().toList();
            this.overflow = contents.size() > this.getContainerSize() ? contents.subList(this.getContainerSize(), contents.size()) : java.util.List.of();
            java.util.Collections.fill(this.getContents(), ItemStack.EMPTY);
            for (int i = 0; i < Math.min(contents.size(), this.getContainerSize()); i++)
                this.getContents().set(i, contents.get(i));
        }

        private void refresh() {
            if (this.source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY) != this.loadedContents)
                this.reload();
        }

        @Override
        public void setChanged() {
            super.setChanged();
            java.util.ArrayList<ItemStack> saved = new java.util.ArrayList<>(this.getContents());
            saved.addAll(this.overflow);
            this.source.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(saved));
            this.loadedContents = this.source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            this.changed.run();
        }
    }

    private static final class Menu extends ShulkerBoxMenu {
        private final ItemStack source;
        private final Predicate<Player> accessible;
        private final Backing backing;

        Menu(int id, net.minecraft.world.entity.player.Inventory inventory, Backing backing, ItemStack source, Predicate<Player> accessible) {
            super(id, inventory, backing);
            this.source = source;
            this.accessible = accessible;
            this.backing = backing;

        }

        @Override
        public boolean stillValid(Player player) {
            return (GeneralCompatConfig.quickShulker || player instanceof org.leavesmc.leaves.bot.ServerBot) && operable(this.source) && this.accessible.test(player);
        }

        @Override
        public void clicked(int index, int button, ContainerInput input, Player player) {
            if (OrgItemShadowGroups.managed(this.source)) {
                OrgItemShadowGroups.actor(player, () -> {
                            var stacks = OrgItemShadowGroups.inventory(player, this.slots);
                            stacks.add(this.source);
                            return stacks;
                        },
                        () -> {
                            this.backing.refresh();
                            this.clickOwned(index, button, input, player);
                            return true;
                        }, false,
                        owned -> player.containerMenu == this && this.stillValid(player), ignored -> this.broadcastChanges());
                return;
            }
            this.clickOwned(index, button, input, player);
        }

        private void clickOwned(int index, int button, ContainerInput input, Player player) {
            if (!this.stillValid(player)) return;
            if (input == ContainerInput.SWAP && (button >= 0 && button < 9 || button == 40)
                    && OrgItemShadowGroups.sameAlias(player.getInventory().getItem(button), this.source)) return;
            if (index >= 0 && index < this.slots.size() && this.isSourceSlot(this.slots.get(index))) {
                if (input == ContainerInput.PICKUP && button == 1 && this.getCarried().getItem().canFitInsideContainerItems()) {
                    this.setCarried(this.backing.addItem(this.getCarried()));
                }
                return;
            }
            super.clicked(index, button, input, player);
        }

        @Override
        public void broadcastChanges() {
            this.backing.refresh();
            super.broadcastChanges();
        }

        private boolean isSourceSlot(Slot slot) {
            return OrgItemShadowGroups.sameAlias(slot.getItem(), this.source);
        }

        @Override
        public boolean canDragTo(Slot slot) {
            return !this.isSourceSlot(slot) && super.canDragTo(slot);
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            return !this.isSourceSlot(slot) && super.canTakeItemForPickAll(stack, slot);
        }
    }
}
