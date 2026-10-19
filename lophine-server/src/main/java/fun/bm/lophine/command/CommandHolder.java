package fun.bm.lophine.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommands;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;

public class CommandHolder {
    private static final Map<String, LiteralArgumentBuilder<CommandSourceStack>> commands = new HashMap<>();

    public static void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        String name = command.getLiteral();
        if (commands.containsKey(name)) {
            unregisterCommand(name);
        }
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().register(command);
        PaperCommands.INSTANCE.invalidate();
        commands.put(name, command);
        Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
    }

    public static void unregisterCommand(String name) {
        commands.remove(name);
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().getRoot().removeCommand(name);
        PaperCommands.INSTANCE.invalidate();
        Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
    }

    public static void onReloadCommands() {
        try {
            PaperCommands.INSTANCE.setValid();
            commands.forEach((name, command) -> {
                PaperCommands.INSTANCE.getDispatcher().register(command);
            });
            PaperCommands.INSTANCE.invalidate();
        } catch (Exception e) {
            LogUtils.getClassLogger().error("Failed to reload commands", e);
        }
    }
}
