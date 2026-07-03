package fun.bm.lophine.bot;

import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.world.SimpleContainer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.entity.bot.CraftBot;

import java.util.HashMap;
import java.util.Map;

public class BotActionGuiContainer extends SimpleContainer {
    private static final Map<String, GuiRootNode> GUI_ROOT_NODE_MAP = new HashMap<>();

    public static void registerGuiRootNode(GuiRootNode guiRootNode) {
        if (!GUI_ROOT_NODE_MAP.containsKey(guiRootNode.getName())) {
            GUI_ROOT_NODE_MAP.put(guiRootNode.getName(), guiRootNode);
        }
    }

    public BotActionGuiContainer(@NotNull CraftBot bot, CraftPlayer player) {

    }
}
