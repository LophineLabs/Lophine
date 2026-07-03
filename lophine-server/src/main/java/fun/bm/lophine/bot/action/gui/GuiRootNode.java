package fun.bm.lophine.bot.action.gui;

import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import net.minecraft.world.item.Item;

import java.rmi.UnexpectedException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class GuiRootNode extends GuiNode {
    protected final Set<GuiNode> children;
    protected final String commandNode;

    public GuiRootNode(String name, String description, Item item, String commandNode) {
        this(name, description, item, new HashSet<>(), commandNode);
    }

    public GuiRootNode(String name, String description, Item item, Set<GuiNode> children, String commandNode) {
        super(name, description, item);
        this.children = children;
        this.commandNode = commandNode;
    }

    public final void child(GuiNode... node) {
        this.children.addAll(List.of(node));
    }

    @SafeVarargs
    public final void child(Supplier<? extends GuiNode>... node) {
        this.children.addAll(Stream.of(node).map(Supplier::get).toList());
    }

    public Set<GuiNode> getChildren() {
        return this.children;
    }

    public String getCommandNode() {
        return this.commandNode;
    }

    public String buildCommand() throws UnexpectedException {
        boolean botCommand = FakeplayerConfig.enable;
        boolean playerCommand = FakePlayerCompatConfig.commandPlayer;
        if (botCommand) {
            return "/bot " + this.getCommandNode();
        }
        if (playerCommand) {
            return "/player " + this.getCommandNode();
        }
        throw new UnexpectedException("Unable to build String from commandNode.");
    }
}
