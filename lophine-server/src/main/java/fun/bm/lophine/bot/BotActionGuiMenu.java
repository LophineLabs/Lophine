package fun.bm.lophine.bot;

import com.mojang.logging.LogUtils;
import fun.bm.lophine.bot.action.gui.ActionType;
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
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.entity.bot.CraftBot;

public class BotActionGuiMenu extends AbstractContainerMenu {
    private final BotActionGuiContainer container;
    private final CraftBot bot;
    private final CraftPlayer player;
    private CraftInventoryView view = null;

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
            CraftInventory inventory = new CraftInventory(this.container);
            this.view = new CraftInventoryView(
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
            if (this.container.isBackButtonSlot(slotIndex)) {
                this.container.navigateBack();
                this.refreshSlots();
                return;
            }

            if (this.container.isHomeButtonSlot(slotIndex)) {
                this.container.navigateHome();
                this.refreshSlots();
                return;
            }

            // Handle command builder click
            if (slotIndex == 49 && this.container.canExecuteCommandBuilder()) {
                this.executeCommandBuilder(player);
                return;
            }

            // Handle ActionType selection
            if (this.container.isSelectingActionType()) {
                ActionType[] actionTypes = ActionType.values();
                // Find which content slot was clicked and map to ActionType index
                int contentIndex = -1;
                for (int i = 0; i < BotActionGuiContainer.CONTENT_SLOTS.length; i++) {
                    if (BotActionGuiContainer.CONTENT_SLOTS[i] == slotIndex) {
                        contentIndex = i;
                        break;
                    }
                }
                if (contentIndex >= 0 && contentIndex < actionTypes.length) {
                    this.container.selectActionType(actionTypes[contentIndex]);
                    this.refreshSlots();
                }
                return;
            }

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
            // Get the selected action type
            ActionType actionType = this.container.getSelectedActionType();
            if (actionType == null) {
                return;
            }

            int maxAllowedParameters = actionType.getMaxAllowedParameters();

            // If the node has children, check parameter limit before navigating
            if (!rootNode.getChildren().isEmpty()) {
                // Calculate current parameter count (navigation stack size + 1 for current node if exists)
                int currentParamCount = this.container.getCurrentParameterCount();

                // If adding this node would exceed the limit, execute command instead of navigating
                if (currentParamCount + 1 > maxAllowedParameters) {
                    this.executeCommand(rootNode, actionType, player);
                } else {
                    this.container.navigateToChild(rootNode);
                    this.refreshSlots();
                }
            } else {
                // Execute command with the selected action type
                this.executeCommand(rootNode, actionType, player);
            }
        }
    }

    private void executeCommand(GuiRootNode node, ActionType actionType, Player player) {
        try {
            String actionPrefix = actionType.getCommandActionPrefix();
            String actionSuffix = actionType.getCommandActionSuffix();
            if (!actionSuffix.isEmpty()) {
                actionSuffix = actionSuffix + " ";
            }

            String extra = actionPrefix + " " + bot.getName() + " " + actionSuffix;
            String command = node.buildCommand(extra);

            // Apply parameter limit based on ActionType
            if (actionType.getMaxAllowedParameters() == 0) {
                // For actions like STOP that don't allow parameters, trim to just "action botName"
                command = actionPrefix + " " + bot.getName();
            }

            if (player instanceof ServerPlayer serverPlayer) {
                MinecraftServer.getServer().getCommands().performPrefixedCommand(
                        serverPlayer.createCommandSourceStack(),
                        command
                );
            }
        } catch (Exception e) {
            LogUtils.getLogger().warn("Error executing command: ", e);
        }
    }

    /**
     * Execute the command from the command builder (book item).
     * Uses the current node and selected action type.
     */
    private void executeCommandBuilder(Player player) {
        GuiNode currentNode = this.container.getCurrentNode();
        ActionType actionType = this.container.getSelectedActionType();

        if (currentNode instanceof GuiRootNode rootNode && actionType != null) {
            this.executeCommand(rootNode, actionType, player);
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
