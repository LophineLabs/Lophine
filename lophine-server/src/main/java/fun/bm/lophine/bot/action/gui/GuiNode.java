package fun.bm.lophine.bot.action.gui;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class GuiNode {
    protected final String name;
    protected final String description;
    protected final ItemStack item;

    private static final ItemStack defaultItem = new ItemStack(Items.PAPER);

    public GuiNode(String name, String description, ItemStack item) {
        this.name = name;
        this.description = description;
        this.item = item;
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public ItemStack getItemStack() {
        return this.item == null ? defaultItem : this.item;
    }
}
