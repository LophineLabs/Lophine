package me.earthme.luminol.commands.config;

import me.earthme.luminol.commands.config.sub.*;
import me.earthme.luminol.config.ConfigsInstance;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.RootNode;

public class ConfigCommand extends RootNode {
    public final ConfigsInstance config;
    public final String name;
    private final String PERM_BASE;

    public ConfigCommand(String name, String commandName, ConfigsInstance config) {
        super(commandName, name + ".commands." + name + "config");
        this.name = name;
        this.PERM_BASE = name + ".commands." + name + "config";
        this.config = config;
        children(
                new ReloadCommand(this),
                new SetCommand(this),
                new ResetCommand(this),
                new OpenGuiCommand(this),
                new SubmitCommand(this),
                new CleanCommand(this),
                new ResetCommentsCommand(this)
        );
    }

    public boolean hasPermission(@NotNull CommandSender sender, String... subcommand) {
        return hasPermission(PERM_BASE, sender, subcommand);
    }

    public String getCommandName() {
        return super.name;
    }
}