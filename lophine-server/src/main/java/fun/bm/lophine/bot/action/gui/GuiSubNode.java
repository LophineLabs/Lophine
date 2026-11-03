package fun.bm.lophine.bot.action.gui;

import net.minecraft.world.item.Item;
import org.leavesmc.leaves.command.WrappedArgument;

import java.rmi.UnexpectedException;
import java.util.ArrayList;
import java.util.List;

public class GuiSubNode extends GuiNode {
    protected final GuiNode parent;
    private final GuiRootNode root;
    private final int depth;

    public GuiSubNode(String name, String description, Item item, GuiNode parent, boolean confirmable) {
        super(name, description, item, confirmable);
        this.parent = parent;
        if (parent instanceof GuiSubNode subNode) {
            this.root = subNode.root;
            this.depth = subNode.depth + 1;
        } else if (parent instanceof GuiRootNode rootNode) {
            this.root = rootNode;
            this.depth = 1;
        } else {
            this.root = null;
            this.depth = 1;
        }
    }

    public GuiSubNode(String name, String description, Item item, GuiNode parent) {
        this(name, description, item, parent, true);
    }

    public GuiNode getParent() {
        return this.parent;
    }

    @Override
    public GuiRootNode getActionRoot() {
        return this.root;
    }

    @Override
    public List<String> collectGuiValues() {
        List<String> values = new ArrayList<>(this.parent.collectGuiValues());
        values.add(this.getName());
        return values;
    }

    @Override
    protected List<WrappedArgument.GuiSuggestion> nextSuggestionLayer() {
        return this.root == null ? List.of() : this.root.layerAt(this.depth);
    }

    @Override
    public String buildCommand(String extra) throws UnexpectedException {
        return this.parent.buildCommand(extra) + " " + this.getName();
    }
}
