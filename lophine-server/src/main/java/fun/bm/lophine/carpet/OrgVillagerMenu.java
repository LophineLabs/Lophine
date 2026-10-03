package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A vanilla 3x3 menu with the villager's eight actual inventory slots. */
public final class OrgVillagerMenu extends DispenserMenu {
    private final Villager villager;

    public OrgVillagerMenu(int id, Inventory inventory, Villager villager) {
        super(id, inventory, new VillagerContainer(villager));
        this.villager = villager;
    }

    @Override
    protected void add3x3GridSlots(Container container, int left, int top) {
        for (int i = 0; i < 9; i++) {
            Slot slot = i == 8 ? new Slot(container, i, left + i % 3 * 18, top + i / 3 * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
                @Override public boolean isActive() { return false; }
            } : new Slot(container, i, left + i % 3 * 18, top + i / 3 * 18);
            this.addSlot(slot);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return TickThread.isTickThreadFor(player) && TickThread.isTickThreadFor(this.villager)
            && this.villager.isAlive() && !this.villager.isRemoved() && player.distanceTo(this.villager) < 8.0F;
    }

    @Override
    public void clicked(int index, int button, ContainerInput input, Player player) {
        if (index == 8 || !this.stillValid(player)) return;
        super.clicked(index, button, input, player);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.index != 8 && super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.index != 8 && super.canTakeItemForPickAll(stack, slot);
    }

    private static final class VillagerContainer extends SimpleContainer {
        private final Villager villager;

        VillagerContainer(Villager villager) {
            super(9);
            this.villager = villager;
        }

        private boolean owned(int slot) {
            return slot >= 0 && slot < 8 && TickThread.isTickThreadFor(this.villager);
        }

        @Override public ItemStack getItem(int slot) { return owned(slot) ? villager.getInventory().getItem(slot) : ItemStack.EMPTY; }
        @Override public ItemStack removeItem(int slot, int amount) { return owned(slot) ? villager.getInventory().removeItem(slot, amount) : ItemStack.EMPTY; }
        @Override public ItemStack removeItemNoUpdate(int slot) { return owned(slot) ? villager.getInventory().removeItemNoUpdate(slot) : ItemStack.EMPTY; }
        @Override public void setItem(int slot, ItemStack stack) { if (owned(slot)) villager.getInventory().setItem(slot, stack); }
        @Override public void setChanged() { if (TickThread.isTickThreadFor(villager)) villager.getInventory().setChanged(); }
        @Override public boolean isEmpty() { return TickThread.isTickThreadFor(villager) && villager.getInventory().isEmpty(); }
        @Override public boolean stillValid(Player player) {
            return TickThread.isTickThreadFor(villager) && villager.isAlive() && !villager.isRemoved() && player.distanceTo(villager) < 8.0F;
        }
    }
}
