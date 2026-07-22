package fun.bm.lophine.bot.action.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public enum ActionType {
    ACTION_START("Start", "Start a action", Items.DYE.lime(), "action", "start"),
    ACTION_STOP("Stop", "Stop a action or see this bot scheduled tasks", Items.DYE.red(), "action", "stop", 0);

    private final String displayName;
    private final String description;
    private final Item item;
    private final String commandActionPrefix;
    private final String commandActionSuffix;
    private final int maxAllowedParameters;

    ActionType(String displayName, String description, Item item, String commandActionPrefix) {
        this(displayName, description, item, commandActionPrefix, "", Integer.MAX_VALUE);
    }

    ActionType(String displayName, String description, Item item, String commandActionPrefix, String commandActionSuffix) {
        this(displayName, description, item, commandActionPrefix, commandActionSuffix, Integer.MAX_VALUE);
    }

    ActionType(String displayName, String description, Item item, String commandActionPrefix, String commandActionSuffix, int maxAllowedParameters) {
        this.displayName = displayName;
        this.description = description;
        this.item = item;
        this.commandActionPrefix = commandActionPrefix;
        this.commandActionSuffix = commandActionSuffix;
        this.maxAllowedParameters = maxAllowedParameters;

    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getDescription() {
        return this.description;
    }

    public Item getItem() {
        return this.item;
    }

    public String getCommandActionPrefix() {
        return this.commandActionPrefix;
    }

    public String getCommandActionSuffix() {
        return commandActionSuffix;
    }

    public GuiNode toConfirmNode(GuiRootNode targetNode) {
        return new ActionConfirmNode(this.displayName, this.description, this.item, targetNode, this.commandActionPrefix, this.maxAllowedParameters);
    }

    public int getMaxAllowedParameters() {
        return maxAllowedParameters;
    }

    /**
     * A special node used in the action type confirmation UI.
     * It holds a reference to the target GuiRootNode and the selected action string.
     */
    public static class ActionConfirmNode extends GuiNode {
        private final GuiRootNode targetNode;
        private final String action;
        private final int maxAllowedParameters;

        public ActionConfirmNode(String name, String description, Item item, GuiRootNode targetNode, String action, int maxAllowedParameters) {
            super(name, description, item);
            this.targetNode = targetNode;
            this.action = action;
            this.maxAllowedParameters = maxAllowedParameters;
        }

        public GuiRootNode getTargetNode() {
            return this.targetNode;
        }

        public String getAction() {
            return this.action;
        }

        public int getMaxAllowedParameters() {
            return this.maxAllowedParameters;
        }

        @Override
        public ItemStack getItemStack() {
            ItemStack itemStack = super.getItemStack();

            // Add parameter limit info to lore
            List<Component> loreLines = new ArrayList<>();
            if (this.description != null && !this.description.isEmpty()) {
                loreLines.add(Component.literal(this.description));
            }

            if (!loreLines.isEmpty()) {
                itemStack.set(DataComponents.LORE, new ItemLore(loreLines));
            }

            return itemStack;
        }
    }
}
