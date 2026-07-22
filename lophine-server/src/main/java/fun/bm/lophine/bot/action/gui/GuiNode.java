package fun.bm.lophine.bot.action.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

public class GuiNode {
    protected final String name;
    protected final String description;
    protected final Item item;
    protected final boolean confirmable;

    private static final Item defaultItem = Items.PAPER;

    public GuiNode(String name, String description, Item item, boolean confirmable) {
        this.name = name;
        this.description = description;
        this.item = item;
        this.confirmable = confirmable;
    }

    public GuiNode(String name, String description, Item item) {
        this(name, description, item, true);
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public boolean isConfirmable() {
        return this.confirmable;
    }

    public ItemStack getItemStack() {
        ItemStack itemStack = new ItemStack(this.item == null ? defaultItem : this.item).copy();
        itemStack.set(DataComponents.CUSTOM_NAME, Component.literal(this.name));
        if (this.description != null && !this.description.isEmpty()) {
            ItemLore lore = new ItemLore(List.of(Component.literal(this.description)));
            itemStack.set(DataComponents.LORE, lore);
        }
        return itemStack;
    }
}
