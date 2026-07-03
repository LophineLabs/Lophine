package fun.bm.lophine.bot;

import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.entity.bot.CraftBot;

public class BotActionGuiMenu extends AbstractContainerMenu {
    private final BotActionGuiContainer container;
    private final CraftBot bot;
    private final CraftPlayer player;
    private org.bukkit.craftbukkit.inventory.CraftInventoryView view = null;

    public BotActionGuiMenu(int containerId, Inventory inventory, BotActionGuiContainer container) {
        super(MenuType.GENERIC_9x6, containerId);
        this.container = container;
        this.bot = container.getBot();
        this.player = container.getPlayer();

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }

                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                });
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }
    }

    @Override
    public org.bukkit.inventory.InventoryView getBukkitView() {
        if (this.view == null) {
            org.bukkit.craftbukkit.inventory.CraftInventory inventory = new org.bukkit.craftbukkit.inventory.CraftInventory(this.container);
            this.view = new org.bukkit.craftbukkit.inventory.CraftInventoryView(
                    this.player,
                    inventory,
                    this
            );
        }
        return this.view;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex >= 0 && slotIndex < 54) {
            GuiNode node = this.container.getGuiNodeAtSlot(slotIndex);
            if (node != null) {
                this.handleNodeClick(node, player);
            }
            return;
        }

        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    private void handleNodeClick(GuiNode node, Player player) {
        if (node instanceof GuiRootNode rootNode) {
            if (!rootNode.getChildren().isEmpty()) {
                this.container.navigateToChild(rootNode);
                this.refreshSlots();
            } else {
                this.executeCommand(rootNode, player);
            }
        }
    }

    private void executeCommand(GuiRootNode node, Player player) {
        try {
            String command = node.buildCommand();
            if (player instanceof ServerPlayer serverPlayer) {
                MinecraftServer.getServer().getCommands().performPrefixedCommand(
                        serverPlayer.createCommandSourceStack(),
                        command.substring(1)
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshSlots() {
        for (int i = 0; i < 54; i++) {
            Slot slot = this.slots.get(i);
            ItemStack item = this.container.getItem(i);
            slot.set(item);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    public BotActionGuiContainer getContainer() {
        return this.container;
    }
}
