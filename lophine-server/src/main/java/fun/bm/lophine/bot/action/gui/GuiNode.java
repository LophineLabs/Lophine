package fun.bm.lophine.bot.action.gui;

import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.leavesmc.leaves.command.LiteralNode;
import org.leavesmc.leaves.command.WrappedArgument;

import java.rmi.UnexpectedException;
import java.util.ArrayList;
import java.util.List;

public class GuiNode extends LiteralNode {
    protected final String displayName;
    protected final String description;
    protected final Item item;
    protected final boolean confirmable;

    private static final Item defaultItem = Items.PAPER;
    private List<GuiNode> resolvedGuiChildren;

    public GuiNode(String name, String displayName, String description, Item item, boolean confirmable) {
        super(name);
        this.displayName = displayName;
        this.description = description;
        this.item = item;
        this.confirmable = confirmable;
    }

    public GuiNode(String name, String description, Item item, boolean confirmable) {
        this(name, name, description, item, confirmable);
    }

    public GuiNode(String name, String description, Item item) {
        this(name, description, item, true);
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public String getDescription() {
        return this.description;
    }

    public boolean isConfirmable() {
        return this.confirmable;
    }

    public GuiRootNode getActionRoot() {
        return null;
    }

    public List<String> collectGuiValues() {
        return List.of();
    }

    public List<GuiNode> getGuiChildren() {
        if (this.resolvedGuiChildren == null) {
            this.resolvedGuiChildren = this.resolveGuiChildren();
        }
        return this.resolvedGuiChildren;
    }

    public boolean hasGuiChildren() {
        return !this.nextSuggestionLayer().isEmpty();
    }

    protected List<WrappedArgument.GuiSuggestion> nextSuggestionLayer() {
        return List.of();
    }

    protected List<GuiNode> resolveGuiChildren() {
        List<WrappedArgument.GuiSuggestion> suggestions = this.nextSuggestionLayer();
        if (suggestions.isEmpty()) {
            return List.of();
        }
        List<GuiNode> children = new ArrayList<>(suggestions.size());
        for (WrappedArgument.GuiSuggestion suggestion : suggestions) {
            children.add(new GuiSubNode(suggestion.value(), suggestion.description(), suggestion.item(), this));
        }
        return children;
    }

    public String buildCommand(String extra) throws UnexpectedException {
        if (FakeplayerConfig.checkEnabled()) {
            return "bot " + extra + this.getName();
        }
        throw new UnexpectedException("Unable to build String from commandNode.");
    }

    public ItemStack getItemStack() {
        ItemStack itemStack = new ItemStack(this.item == null ? defaultItem : this.item).copy();
        itemStack.set(DataComponents.CUSTOM_NAME, Component.literal(this.displayName));
        if (this.description != null && !this.description.isEmpty()) {
            ItemLore lore = new ItemLore(List.of(Component.literal(this.description)));
            itemStack.set(DataComponents.LORE, lore);
        }
        return itemStack;
    }
}
