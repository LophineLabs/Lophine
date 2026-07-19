package fun.bm.lophine.bot;

import fun.bm.lophine.bot.action.gui.ActionType;
import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
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
import org.leavesmc.leaves.entity.bot.action.BotAction;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;

import java.util.*;

public class BotActionGuiContainer extends SimpleContainer {
    private static final Map<String, GuiRootNode> GUI_ROOT_NODE_MAP = new LinkedHashMap<>();

    private static final int CONTAINER_SIZE = 54;

    private static final int[] BORDER_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 17,
            18, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53
    };

    public static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private static final int BACK_BUTTON_SLOT = 38;
    private static final int COMMAND_BUILDER_SLOT = 40;
    private static final int HOME_BUTTON_SLOT = 42;
    private static final int PREV_PAGE_SLOT = 39;
    private static final int NEXT_PAGE_SLOT = 41;

    private static final ItemStack boarder = new ItemStack(Items.WHITE_STAINED_GLASS_PANE);

    private final CraftBot bot;
    private final CraftPlayer player;
    private final Deque<GuiNode> navigationStack = new ArrayDeque<>();
    private final Deque<Boolean> fromRootLevelStack = new ArrayDeque<>(); // Track if we came from root level
    private GuiNode currentNode = null;

    // New state for action type selection flow
    private ActionType selectedActionType = null;
    private boolean isSelectingActionType = false;

    // Pagination state
    private int currentPage = 0;

    public static void registerGuiRootNode(GuiRootNode guiRootNode) {
        if (!GUI_ROOT_NODE_MAP.containsKey(guiRootNode.getName())) {
            GUI_ROOT_NODE_MAP.put(guiRootNode.getName(), guiRootNode);
        }
    }

    public static GuiRootNode getGuiRootNode(String name) {
        return GUI_ROOT_NODE_MAP.get(name);
    }

    public static Map<String, GuiRootNode> getGuiRootNodes() {
        return new LinkedHashMap<>(GUI_ROOT_NODE_MAP);
    }

    public BotActionGuiContainer(@NotNull CraftBot bot, CraftPlayer player) {
        super(CONTAINER_SIZE, player);
        this.bot = bot;
        this.player = player;
        this.showActionTypes();
    }

    public boolean isCommandBuilderSlot(int id) {
        return id == COMMAND_BUILDER_SLOT;
    }

    private void fillBorder() {
        for (int slot : BORDER_SLOTS) {
            this.setItem(slot, boarder);
        }
    }

    private void showActionTypes() {
        this.clearContent();
        this.fillBorder();
        this.currentNode = null;
        this.navigationStack.clear();
        this.fromRootLevelStack.clear();
        this.selectedActionType = null;
        this.isSelectingActionType = true;
        this.currentPage = 0;

        List<ActionType> allTypes = List.of(ActionType.values());
        this.renderPagedItems(allTypes, (actionType, slot) -> {
            ItemStack item = actionType.toConfirmNode(null).getItemStack();
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
        });

        this.addNavigationButtons();
    }

    private void showRootNodes() {
        this.clearContent();
        this.fillBorder();
        this.currentNode = null;
        this.navigationStack.clear();
        this.fromRootLevelStack.clear();
        this.isSelectingActionType = false;
        this.currentPage = 0;

        List<GuiRootNode> allNodes = new ArrayList<>(GUI_ROOT_NODE_MAP.values());
        if (allNodes.isEmpty()) {
            // No root nodes, go back to action type selection
            this.showActionTypes();
            return;
        }

        this.renderPagedItems(allNodes, (node, slot) -> {
            ItemStack item = createNodeItemWithRunHint(node);
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
        });

        this.addNavigationButtons();
    }

    public void navigateToChild(GuiNode node) {
        if (node == null) {
            return;
        }

        // If we're at root level (currentNode is null but not selecting action type),
        // we need to track that we came from root nodes level
        if (this.currentNode != null) {
            this.navigationStack.push(this.currentNode);
            this.fromRootLevelStack.push(false);
        } else if (!this.isSelectingActionType && this.selectedActionType != null) {
            // We're at root nodes level, mark that we should return to root nodes
            this.fromRootLevelStack.push(true);
        }

        this.currentNode = node;
        this.currentPage = 0;
        this.refreshContainer();
    }

    public boolean navigateBack() {
        // If we're at root node level (no navigation stack), go back to action type selection
        if (this.navigationStack.isEmpty() && this.fromRootLevelStack.isEmpty()) {
            // If we have a selected action type, we're at the root nodes level
            if (!this.isSelectingActionType && this.selectedActionType != null) {
                this.showActionTypes();
                return true;
            }
            // Already at action type selection
            return false;
        }

        // Check if we should return to root nodes level
        boolean fromRootLevel = !this.fromRootLevelStack.isEmpty() && this.fromRootLevelStack.pop();

        if (fromRootLevel) {
            // Return to root nodes level
            this.currentNode = null;
            this.showRootNodes();
        } else if (!this.navigationStack.isEmpty()) {
            // Pop from navigation stack and refresh
            this.currentNode = this.navigationStack.pop();
            this.refreshContainer();
        }
        return true;
    }

    public void navigateHome() {
        this.showActionTypes();
    }

    private void refreshContainer() {
        this.clearContent();
        this.fillBorder();

        Set<GuiNode> children = this.getChildrenOfCurrentNode();
        if (children == null || children.isEmpty()) {
            // Empty page: auto-navigate back
            this.navigateBack();
            return;
        }

        List<GuiNode> childList = new ArrayList<>(children);
        this.renderPagedItems(childList, (node, slot) -> {
            ItemStack item = createNodeItemWithRunHint(node);
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
        });

        this.addNavigationButtons();
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
        if (slot == BACK_BUTTON_SLOT && this.canNavigateBack()) {
            return null; // back button is handled separately
        }

        // Check if slot is a border slot
        for (int borderSlot : BORDER_SLOTS) {
            if (borderSlot == slot) {
                return null;
            }
        }

        Set<GuiNode> nodes = this.getCurrentDisplayNodes();
        if (nodes == null) {
            return null;
        }

        // Find the content index for this slot
        int contentIndex = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == slot) {
                contentIndex = i;
                break;
            }
        }

        if (contentIndex == -1) {
            return null;
        }

        // Apply pagination offset
        int actualIndex = this.currentPage * CONTENT_SLOTS.length + contentIndex;
        int index = 0;
        for (GuiNode node : nodes) {
            if (index == actualIndex) {
                return node;
            }
            index++;
        }
        return null;
    }

    public boolean isBackButtonSlot(int slot) {
        return slot == BACK_BUTTON_SLOT && this.canNavigateBack();
    }

    public boolean isHomeButtonSlot(int slot) {
        return slot == HOME_BUTTON_SLOT && this.canNavigateHome();
    }

    private static ItemStack createBackButtonItem() {
        ItemStack item = new ItemStack(Items.RED_WOOL);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("§cBack"));
        item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Return to previous page"))));
        return item;
    }

    private static ItemStack createHomeButtonItem() {
        ItemStack item = new ItemStack(Items.RED_BED);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("§aHome"));
        item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Return to main menu"))));
        return item;
    }

    private ItemStack createNodeItemWithRunHint(GuiNode node) {
        ItemStack itemStack = node.getItemStack();

        // Check if this is a last-level node (no children)
        boolean isLastLevel;
        if (node instanceof GuiRootNode rootNode) {
            isLastLevel = rootNode.getChildren().isEmpty();
        } else {
            // Non-RootNode is always considered last level
            isLastLevel = true;
        }

        // If it's the last level and we have an action type selected, add run hint
        if (isLastLevel && this.selectedActionType != null && !this.isSelectingActionType) {
            // Get existing lore or create new one
            ItemLore existingLore = itemStack.get(DataComponents.LORE);
            List<Component> loreLines = new ArrayList<>();

            if (existingLore != null) {
                loreLines.addAll(existingLore.lines());
            }

            // Add golden run hint
            loreLines.add(Component.literal("§6Click to execute"));

            itemStack.set(DataComponents.LORE, new ItemLore(loreLines));
        }

        return itemStack;
    }

    /**
     * Render items with pagination support.
     *
     * @param items    the full list of items
     * @param renderer callback to render each item at a specific slot
     */
    private <T> void renderPagedItems(List<T> items, PagedItemRenderer<T> renderer) {
        int pageSize = CONTENT_SLOTS.length;
        int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);

        // Clamp current page
        if (this.currentPage >= totalPages) {
            this.currentPage = totalPages - 1;
        }
        if (this.currentPage < 0) {
            this.currentPage = 0;
        }

        int start = this.currentPage * pageSize;
        int end = Math.min(start + pageSize, items.size());

        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            renderer.render(items.get(i), CONTENT_SLOTS[slotIndex]);
        }
    }

    @FunctionalInterface
    private interface PagedItemRenderer<T> {
        void render(T item, int slot);
    }

    /**
     * Add navigation buttons including pagination controls.
     */
    private void addNavigationButtons() {
        if (this.canNavigateBack()) {
            this.setItem(BACK_BUTTON_SLOT, createBackButtonItem());
        }

        this.setItem(COMMAND_BUILDER_SLOT, createCommandBuilderItem());

        if (this.canNavigateHome()) {
            this.setItem(HOME_BUTTON_SLOT, createHomeButtonItem());
        }

        // Pagination buttons
        if (this.hasPrevPage()) {
            this.setItem(PREV_PAGE_SLOT, createPrevPageItem());
        }
        if (this.hasNextPage()) {
            this.setItem(NEXT_PAGE_SLOT, createNextPageItem());
        }
    }

    private boolean hasPrevPage() {
        return this.currentPage > 0;
    }

    private boolean hasNextPage() {
        int totalItems = this.getCurrentTotalItemCount();
        int totalPages = Math.max(1, (totalItems + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
        return this.currentPage < totalPages - 1;
    }

    private int getCurrentTotalItemCount() {
        if (this.isSelectingActionType) {
            return ActionType.values().length;
        }
        if (this.selectedActionType == ActionType.ACTION_STOP) {
            return this.bot.getActionSize();
        }
        if (this.currentNode == null) {
            return GUI_ROOT_NODE_MAP.size();
        }
        Set<GuiNode> children = this.getChildrenOfCurrentNode();
        return children != null ? children.size() : 0;
    }

    public void prevPage() {
        if (this.hasPrevPage()) {
            this.currentPage--;
            this.redisplayCurrentViewWithoutReset();
        }
    }

    public void nextPage() {
        if (this.hasNextPage()) {
            this.currentPage++;
            this.redisplayCurrentViewWithoutReset();
        }
    }

    public boolean isPrevPageSlot(int slot) {
        return slot == PREV_PAGE_SLOT && this.hasPrevPage();
    }

    public boolean isNextPageSlot(int slot) {
        return slot == NEXT_PAGE_SLOT && this.hasNextPage();
    }

    /**
     * Re-render the current view without resetting navigation state.
     */
    private void redisplayCurrentView() {
        if (this.isSelectingActionType) {
            this.showActionTypes();
        } else if (this.selectedActionType == ActionType.ACTION_STOP) {
            this.showCurrentBotActions();
        } else if (this.currentNode != null) {
            this.refreshContainer();
        } else {
            this.showRootNodes();
        }
    }

    /**
     * Re-render the current view without resetting pagination state.
     * Used for page navigation to preserve currentPage value.
     */
    private void redisplayCurrentViewWithoutReset() {
        this.clearContent();
        this.fillBorder();

        if (this.isSelectingActionType) {
            List<ActionType> allTypes = List.of(ActionType.values());
            this.renderPagedItems(allTypes, (actionType, slot) -> {
                ItemStack item = actionType.toConfirmNode(null).getItemStack();
                if (item != null && !item.isEmpty()) {
                    this.setItem(slot, item.copy());
                }
            });
        } else if (this.selectedActionType == ActionType.ACTION_STOP) {
            int actionSize = this.bot.getActionSize();
            if (actionSize > 0) {
                List<Integer> actionIndices = new ArrayList<>();
                for (int i = 0; i < actionSize; i++) {
                    actionIndices.add(i);
                }
                this.renderPagedItems(actionIndices, (index, slot) -> {
                    org.leavesmc.leaves.entity.bot.action.BotAction<?> action = this.bot.getAction(index);
                    ItemStack item = createActionItem(action, index);
                    if (item != null && !item.isEmpty()) {
                        this.setItem(slot, item.copy());
                    }
                });
            }
        } else if (this.currentNode != null) {
            Set<GuiNode> children = this.getChildrenOfCurrentNode();
            if (children != null && !children.isEmpty()) {
                List<GuiNode> childList = new ArrayList<>(children);
                this.renderPagedItems(childList, (node, slot) -> {
                    ItemStack item = createNodeItemWithRunHint(node);
                    if (item != null && !item.isEmpty()) {
                        this.setItem(slot, item.copy());
                    }
                });
            }
        } else {
            List<GuiRootNode> allNodes = new ArrayList<>(GUI_ROOT_NODE_MAP.values());
            if (!allNodes.isEmpty()) {
                this.renderPagedItems(allNodes, (node, slot) -> {
                    ItemStack item = createNodeItemWithRunHint(node);
                    if (item != null && !item.isEmpty()) {
                        this.setItem(slot, item.copy());
                    }
                });
            }
        }

        this.addNavigationButtons();
    }

    private static ItemStack createPrevPageItem() {
        ItemStack item = new ItemStack(Items.ARROW);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("§ePrevious Page"));
        return item;
    }

    private static ItemStack createNextPageItem() {
        ItemStack item = new ItemStack(Items.ARROW);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext Page"));
        return item;
    }

    private ItemStack createCommandBuilderItem() {
        ItemStack item = new ItemStack(Items.BOOK);

        // Build the complete command preview
        String commandPreview = buildCommandPreview();

        // Check if current node is runnable (confirmable)
        boolean canRun = this.currentNode != null && this.currentNode.isConfirmable();

        List<Component> loreLines = new ArrayList<>();
        loreLines.add(Component.literal("§eCommand:"));
        loreLines.add(Component.literal("§f" + commandPreview));

        // Add run hint if the node is confirmable
        if (canRun) {
            loreLines.add(Component.literal("§6Click to execute"));
        }

        item.set(DataComponents.CUSTOM_NAME, Component.literal("§6Command Builder"));
        item.set(DataComponents.LORE, new ItemLore(loreLines));
        return item;
    }

    private String buildCommandPreview() {
        if (this.selectedActionType == null) {
            return "Select an action type first";
        }

        // For STOP action type, show different preview
        if (this.selectedActionType == ActionType.ACTION_STOP) {
            int actionSize = this.bot.getActionSize();
            if (actionSize == 0) {
                return "No actions to stop";
            }
            return "Click an action to stop it (" + actionSize + " active)";
        }

        try {
            String actionPrefix = this.selectedActionType.getCommandActionPrefix();
            String actionSuffix = this.selectedActionType.getCommandActionSuffix();
            if (!actionSuffix.isEmpty()) {
                actionSuffix = actionSuffix + " ";
            }

            String extra = actionPrefix + " " + this.bot.getName() + " " + actionSuffix;

            // If we have a current node and it's a GuiRootNode, build the command
            if (this.currentNode instanceof GuiRootNode rootNode) {
                String command = rootNode.buildCommand(extra);

                // Apply parameter limit based on ActionType
                if (this.selectedActionType.getMaxAllowedParameters() == 0) {
                    command = actionPrefix + " " + this.bot.getName();
                }

                return command;
            } else if (!this.isSelectingActionType) {
                // At root nodes level or selecting action type
                return extra.trim() + " <command>";
            } else {
                return "Select a command node";
            }
        } catch (Exception e) {
            return "Error building command";
        }
    }

    @Nullable
    private Set<GuiNode> getCurrentDisplayNodes() {
        // If selecting action type, return null (handled separately)
        if (this.isSelectingActionType) {
            return null;
        }
        if (this.currentNode == null) {
            return new LinkedHashSet<>(GUI_ROOT_NODE_MAP.values());
        }
        return this.getChildrenOfCurrentNode();
    }

    /**
     * Called when user selects an ActionType (START/STOP)
     */
    public void selectActionType(ActionType actionType) {
        this.selectedActionType = actionType;
        this.isSelectingActionType = false;

        // If STOP action type is selected, show current bot actions instead of root nodes
        if (actionType == ActionType.ACTION_STOP) {
            this.showCurrentBotActions();
        } else {
            this.showRootNodes();
        }
    }

    /**
     * Show the current bot's scheduled actions for stopping
     */
    public void showCurrentBotActions() {
        this.clearContent();
        this.fillBorder();
        this.currentNode = null;
        this.navigationStack.clear();
        this.fromRootLevelStack.clear();
        this.isSelectingActionType = false;
        this.currentPage = 0;

        int actionSize = this.bot.getActionSize();
        if (actionSize == 0) {
            // No actions to stop, auto-navigate back to action type selection
            this.showActionTypes();
            return;
        }

        // Build list of action indices for pagination
        List<Integer> actionIndices = new ArrayList<>();
        for (int i = 0; i < actionSize; i++) {
            actionIndices.add(i);
        }

        this.renderPagedItems(actionIndices, (index, slot) -> {
            org.leavesmc.leaves.entity.bot.action.BotAction<?> action = this.bot.getAction(index);
            ItemStack item = createActionItem(action, index);
            if (item != null && !item.isEmpty()) {
                this.setItem(slot, item.copy());
            }
        });

        this.addNavigationButtons();
    }

    /**
     * Create an ItemStack representing a bot action for display in the GUI
     */
    private ItemStack createActionItem(BotAction<?> action, int index) {
        ItemStack item = new ItemStack(Items.PAPER);
        String actionName = action.getName();
        String actionHash = action.getUUID().toString();

        item.set(DataComponents.CUSTOM_NAME, Component.literal("§c" + actionName));

        // Store action hash in CUSTOM_DATA for later retrieval
        CompoundTag customTag = new CompoundTag();
        customTag.putString("action_hash", actionHash);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(customTag));

        List<Component> loreLines = new ArrayList<>();
        CompoundTag nbt = new CompoundTag();
        ((CraftBotAction<?, ?>) action).getHandle().save(nbt);
        nbt.forEach((key, tag) -> loreLines.add(Component.literal("§7" + key + ": §f" + tag)));
        loreLines.add(Component.literal("§7Index: §f" + index));
        loreLines.add(Component.literal("§7Hash: §f" + actionHash.substring(0, 8)));
        loreLines.add(Component.literal("§6Click to stop this action"));

        item.set(DataComponents.LORE, new ItemLore(loreLines));
        return item;
    }

    /**
     * Get the action hash (UUID string) at a specific slot (for STOP action type)
     * Returns null if not found or not in STOP mode
     */
    @Nullable
    public String getActionHashAtSlot(int slot) {
        if (this.selectedActionType != ActionType.ACTION_STOP) {
            return null;
        }

        // Read the action hash directly from the ItemStack's CUSTOM_DATA
        ItemStack item = this.getItem(slot);
        if (item.isEmpty()) {
            return null;
        }

        CustomData customData = item.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }

        CompoundTag tag = customData.copyTag();
        if (tag.contains("action_hash")) {
            return tag.getString("action_hash").get();
        }

        return null;
    }

    /**
     * Get the currently selected ActionType
     */
    @Nullable
    public ActionType getSelectedActionType() {
        return this.selectedActionType;
    }

    /**
     * Check if we're in action type selection mode
     */
    public boolean isSelectingActionType() {
        return this.isSelectingActionType;
    }

    public boolean canNavigateBack() {
        return !this.navigationStack.isEmpty() || (!this.isSelectingActionType && this.selectedActionType != null);
    }

    public boolean canNavigateHome() {
        return !this.isSelectingActionType && (this.currentNode != null || this.selectedActionType != null);
    }

    /**
     * Check if the command builder can be executed.
     * Returns true if there is a current node and it is confirmable.
     */
    public boolean canExecuteCommandBuilder() {
        return this.currentNode != null && this.currentNode.isConfirmable();
    }

    @Nullable
    public GuiNode getCurrentNode() {
        return this.currentNode;
    }

    /**
     * Get the current parameter count (number of selected command nodes).
     * This equals the navigation stack size plus 1 if currentNode is not null.
     */
    public int getCurrentParameterCount() {
        return this.navigationStack.size() + (this.currentNode != null ? 1 : 0);
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
