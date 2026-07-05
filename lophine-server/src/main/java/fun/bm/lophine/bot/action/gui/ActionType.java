package fun.bm.lophine.bot.action.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ActionType {
    START("Start", "Start a action", Items.LIME_DYE, "start"),
    STOP("Stop", "Stop a action", Items.RED_DYE, "stop");

    private final String displayName;
    private final String description;
    private final Item item;
    private final String commandAction;

    ActionType(String displayName, String description, Item item, String commandAction) {
        this.displayName = displayName;
        this.description = description;
        this.item = item;
        this.commandAction = commandAction;
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

    public String getCommandAction() {
        return this.commandAction;
    }

    public GuiNode toConfirmNode(GuiRootNode targetNode) {
        return new ActionConfirmNode(this.displayName, this.description, this.item, targetNode, this.commandAction);
    }

    /**
     * A special node used in the action type confirmation UI.
     * It holds a reference to the target GuiRootNode and the selected action string.
     */
    public static class ActionConfirmNode extends GuiNode {
        private final GuiRootNode targetNode;
        private final String action;

        public ActionConfirmNode(String name, String description, Item item, GuiRootNode targetNode, String action) {
            super(name, description, item);
            this.targetNode = targetNode;
            this.action = action;
        }

        public GuiRootNode getTargetNode() {
            return this.targetNode;
        }

        public String getAction() {
            return this.action;
        }
    }
}
