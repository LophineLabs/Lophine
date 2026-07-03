package fun.bm.lophine.bot;

import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.entity.bot.CraftBot;

import java.util.*;

public class BotActionGuiContainer extends SimpleContainer {
    private static final Map<String, GuiRootNode> GUI_ROOT_NODE_MAP = new HashMap<>();
    private static final int CONTAINER_SIZE = 54;
    private static final int BACK_BUTTON_SLOT = 45;

    private final CraftBot bot;
    private final CraftPlayer player;
    private final Deque<GuiNode> navigationStack = new ArrayDeque<>();
    private GuiNode currentNode = null;

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
        if (this.navigationStack.isEmpty()) {
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

        if (!this.navigationStack.isEmpty()) {
            // TODO
        }
    }

    @Nullable
    private Set<GuiNode> getChildrenOfCurrentNode() {
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

    @Nullable
    private Set<GuiNode> getCurrentDisplayNodes() {
        if (this.currentNode == null) {
            return new HashSet<>(GUI_ROOT_NODE_MAP.values());
        }
        return this.getChildrenOfCurrentNode();
    }

    public boolean canNavigateBack() {
        return !this.navigationStack.isEmpty();
    }

    @Nullable
    public GuiNode getCurrentNode() {
        return this.currentNode;
    }

    public boolean isAtRoot() {
        return this.currentNode == null;
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
