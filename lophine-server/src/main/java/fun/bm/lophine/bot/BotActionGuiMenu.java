package fun.bm.lophine.bot;

import com.mojang.logging.LogUtils;
import fun.bm.lophine.bot.action.gui.ActionType;
import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.Actions;
import org.leavesmc.leaves.bot.agent.actions.AbstractBotAction;
import org.leavesmc.leaves.command.bot.BotCommand;
import org.leavesmc.leaves.command.bot.subcommands.action.StartCommand;
import org.leavesmc.leaves.command.bot.subcommands.action.StopCommand;
import org.leavesmc.leaves.entity.bot.CraftBot;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

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

            if (this.container.isPrevPageSlot(slotIndex)) {
                this.container.prevPage();
                this.refreshSlots();
                return;
            }
            if (this.container.isNextPageSlot(slotIndex)) {
                this.container.nextPage();
                this.refreshSlots();
                return;
            }

            if (this.container.isCommandBuilderSlot(slotIndex) && this.container.canExecuteCommandBuilder()) {
                this.executeCommandBuilder();
                this.refreshSlots();
                return;
            }

            if (this.container.isSelectingActionType()) {
                ActionType clickedType = this.container.getActionTypeAtSlot(slotIndex);
                if (clickedType != null) {
                    this.container.selectActionType(clickedType);
                    this.refreshSlots();
                }
                return;
            }

            if (this.container.getSelectedActionType() == ActionType.ACTION_STOP) {
                String actionHash = this.container.getActionHashAtSlot(slotIndex);
                if (actionHash != null) {
                    this.stopActionByHash(actionHash);
                    this.refreshSlots();
                }
                return;
            }

            GuiNode node = this.container.getGuiNodeAtSlot(slotIndex);
            if (node != null) {
                this.handleNodeClick(node);
                this.refreshSlots();
            }
            return;
        }

        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    private void handleNodeClick(GuiNode node) {
        ActionType actionType = this.container.getSelectedActionType();
        if (actionType == null) {
            return;
        }

        int maxAllowedParameters = actionType.getMaxAllowedParameters();
        if (node.hasGuiChildren()) {
            int currentParamCount = this.container.getCurrentParameterCount();
            if (currentParamCount + 1 > maxAllowedParameters) {
                this.executeNode(node, actionType);
            } else {
                this.container.navigateToChild(node);
            }
        } else {
            this.executeNode(node, actionType);
        }
    }

    private void executeNode(GuiNode node, ActionType actionType) {
        if (actionType == ActionType.ACTION_START) {
            this.startAction(node);
        }
    }

    private boolean canUseAction() {
        CommandSender sender = this.player;
        if (!FakeplayerConfig.checkEnabled() || !FakeplayerConfig.canUseAction || !BotCommand.hasPermission(sender, "action")) {
            sender.sendMessage(text("You do not have permission to use bot actions", RED));
            return false;
        }
        return true;
    }

    private void startAction(GuiNode node) {
        if (!this.canUseAction()) {
            return;
        }

        GuiRootNode root = node.getActionRoot();
        if (root == null) {
            return;
        }
        Actions<?> holder = Actions.getByName(root.getName());
        if (holder == null) {
            return;
        }

        try {
            AbstractBotAction<?> action = holder.create();
            action.loadGui(node.collectGuiValues());
            StartCommand.start(this.bot.getHandle(), this.player, holder, action);
        } catch (Exception e) {
            LogUtils.getLogger().warn("Error starting bot action from GUI: ", e);
            this.player.sendMessage(text("Failed to start bot action", RED));
            return;
        }

        if (this.player.getHandle() instanceof ServerPlayer serverPlayer) {
            serverPlayer.closeContainer();
        }
    }

    private void executeCommandBuilder() {
        GuiNode currentNode = this.container.getCurrentNode();
        ActionType actionType = this.container.getSelectedActionType();
        if (currentNode != null && actionType != null) {
            this.executeNode(currentNode, actionType);
        }
    }

    private void stopActionByHash(String actionHash) {
        if (this.canUseAction()) {
            ServerBot serverBot = this.bot.getHandle();
            AbstractBotAction<?> action = StopCommand.findByHash(serverBot, actionHash);
            if (action != null) {
                StopCommand.stopAction(serverBot, this.player, action);
            }
        }
        this.container.showCurrentBotActions();
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
