package fun.bm.lophine.bot.action.gui;

import net.minecraft.world.item.Item;

import java.rmi.UnexpectedException;
import java.util.HashSet;
import java.util.Set;

public class GuiSubNode extends GuiRootNode {
    protected final GuiNode parent;

    public GuiSubNode(String name, String description, Item item, GuiNode parent, Set<GuiNode> children, String commandNode) {
        this(name, description, item, parent, new HashSet<>(), commandNode, true);
    }

    public GuiSubNode(String name, String description, Item item, GuiNode parent, Set<GuiNode> children, String commandNode, boolean confirmable) {
        super(name, description, item, children, commandNode, confirmable);
        this.parent = parent;
    }

    public GuiSubNode(String name, String description, Item item, GuiNode parent, String commandNode) {
        this(name, description, item, parent, commandNode, true);
    }

    public GuiSubNode(String name, String description, Item item, GuiNode parent, String commandNode, boolean confirmable) {
        this(name, description, item, parent, new HashSet<>(), commandNode, confirmable);
    }

    public GuiNode getParent() {
        return this.parent;
    }

    @Override
    public String buildCommand(String extra) throws UnexpectedException {
        return ((GuiRootNode) parent).buildCommand(extra) + " " + getCommandNode();
    }
}
