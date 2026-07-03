package fun.bm.lophine.bot.action.gui;

import net.minecraft.world.item.ItemStack;

import java.rmi.UnexpectedException;
import java.util.Set;

public class GuiSubNode extends GuiRootNode {
    protected final GuiNode parent;

    public GuiSubNode(String name, String description, ItemStack item, GuiNode parent, Set<GuiNode> children, String commandNode) {
        super(name, description, item, children, commandNode);
        this.parent = parent;
    }

    public GuiSubNode(String name, String description, ItemStack item, GuiNode parent, String commandNode) {
        super(name, description, item, commandNode);
        this.parent = parent;
    }

    public GuiNode getParent() {
        return this.parent;
    }

    @Override
    public String buildCommand() throws UnexpectedException {
        return ((GuiRootNode) parent).buildCommand() + getCommandNode();
    }
}
