package fun.bm.lophine.bot;

import fun.bm.lophine.bot.action.gui.ActionType;
import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.entity.bot.CraftBot;

import java.util.*;

public class BotActionGuiContainer extends SimpleContainer {
    private static final Map<String, GuiRootNode> GUI_ROOT_NODE_MAP = new HashMap<>();
    private static final int CONTAINER_SIZE = 54;
    private static final int BACK_BUTTON_SLOT = 50;

    private final CraftBot bot;
    private final CraftPlayer player;
    private final Deque<GuiNode> navigationStack = new ArrayDeque<>();
    private GuiNode currentNode = null;
    private GuiRootNode pendingConfirmNode = null;
    private List<GuiNode> confirmNodes = null;

    public static void registerGuiRootNode(GuiRootNode guiRootNode) {
        if (!GUI_ROOT_NODE_MAP.containsKey(guiRootNode.getName())) {
            GUI_ROOT_NODE_MAP.put(guiRootNode.getName(), guiRootNode);
        }
    }

    public static GuiRootNode getGuiRootNode(String name) {
        return GUI_ROOT_NODE_MAP.get(name);
    }

    public static Map<String, GuiRootNode> getGuiRootNodes() {
        return new HashMap<>(GUI_ROOT_NODE_MAP);
    }

    public BotActionGuiContainer(@NotNull CraftBot bot, CraftPlayer player) {
        super(CONTAINER_SIZE, player);
        this.bot = bot;
        this.player = player;
        this.showRootNodes();
    }

    private void showRootNodes() {
        this.clearContent();
        this.currentNode = null;
        this.navigationStack.clear();
        this.pendingConfirmNode = null;
        this.confirmNodes = null;

        int slot = 0;
        for (GuiRootNode rootNode : GUI_ROOT_NODE_MAP.values()) {
            if (slot >= CONTAINER_SIZE) {
                break;
            }
            ItemStack item = rootNode.getItemStack();
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
            slot++;
        }
    }

    public void navigateToChild(GuiNode node) {
        if (node == null) {
            return;
        }

        if (this.currentNode != null) {
            this.navigationStack.push(this.currentNode);
        }

        this.currentNode = node;
        this.refreshContainer();
    }

    public boolean navigateBack() {
        if (this.isConfirmMode()) {
            this.pendingConfirmNode = null;
            this.confirmNodes = null;
        }

        if (this.navigationStack.isEmpty()) {
            this.currentNode = null;
            this.showRootNodes();
            return false;
        }

        this.currentNode = this.navigationStack.pop();
        this.refreshContainer();
        return true;
    }

    private void refreshContainer() {
        this.clearContent();

        Set<GuiNode> children = this.getChildrenOfCurrentNode();
        if (children == null || children.isEmpty()) {
            this.showRootNodes();
            return;
        }

        int slot = 0;
        for (GuiNode child : children) {
            if (slot >= CONTAINER_SIZE - 1) {
                break;
            }
            ItemStack item = child.getItemStack();
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
            slot++;
        }

        if (this.canNavigateBack()) {
            this.setItem(BACK_BUTTON_SLOT, createBackButtonItem());
        }
    }

    @Nullable
    private Set<GuiNode> getChildrenOfCurrentNode() {
        if (this.isConfirmMode()) {
            return this.confirmNodes != null ? new LinkedHashSet<>(this.confirmNodes) : null;
        }
        if (this.currentNode == null) {
            return null;
        }
        if (this.currentNode instanceof GuiRootNode rootNode) {
            return rootNode.getChildren();
        }
        return null;
    }

    @Nullable
    public GuiNode getGuiNodeAtSlot(int slot) {
        if (slot == BACK_BUTTON_SLOT && this.canNavigateBack()) {
            return null; // back button is handled separately
        }

        Set<GuiNode> nodes = this.getCurrentDisplayNodes();
        if (nodes == null) {
            return null;
        }

        int index = 0;
        for (GuiNode node : nodes) {
            if (index == slot) {
                return node;
            }
            index++;
        }
        return null;
    }

    public boolean isBackButtonSlot(int slot) {
        return slot == BACK_BUTTON_SLOT && this.canNavigateBack();
    }

    private static ItemStack createBackButtonItem() {
        ItemStack item = new ItemStack(Items.BARRIER);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("§cBack"));
        item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Return to previous page"))));
        return item;
    }

    @Nullable
    private Set<GuiNode> getCurrentDisplayNodes() {
        if (this.isConfirmMode()) {
            return this.confirmNodes != null ? new LinkedHashSet<>(this.confirmNodes) : null;
        }
        if (this.currentNode == null) {
            return new HashSet<>(GUI_ROOT_NODE_MAP.values());
        }
        return this.getChildrenOfCurrentNode();
    }

    public void enterActionConfirm(GuiRootNode targetNode) {
        if (this.currentNode != null) {
            this.navigationStack.push(this.currentNode);
        }
        this.pendingConfirmNode = targetNode;
        this.confirmNodes = new ArrayList<>();
        for (ActionType actionType : ActionType.values()) {
            this.confirmNodes.add(actionType.toConfirmNode(targetNode));
        }
        this.currentNode = null;
        this.refreshContainer();
    }

    public boolean isConfirmMode() {
        return this.pendingConfirmNode != null;
    }

    @Nullable
    public GuiRootNode getPendingConfirmNode() {
        return this.pendingConfirmNode;
    }

    public boolean canNavigateBack() {
        return !this.navigationStack.isEmpty() || this.isConfirmMode();
    }

    @Nullable
    public GuiNode getCurrentNode() {
        return this.currentNode;
    }

    public boolean isAtRoot() {
        return this.currentNode == null && !this.isConfirmMode();
    }

    public CraftBot getBot() {
        return this.bot;
    }

    public CraftPlayer getPlayer() {
        return this.player;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.player.getHandle() == player && this.bot.isValid();
    }
}
