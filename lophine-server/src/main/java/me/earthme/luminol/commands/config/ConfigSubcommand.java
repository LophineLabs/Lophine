package me.earthme.luminol.commands.config;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.LiteralNode;

public abstract class ConfigSubcommand extends LiteralNode {
    protected final ConfigCommand parent;

    protected ConfigSubcommand(String name, ConfigCommand parent) {
        super(name);
        this.parent = parent;
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return hasPermission(source.getSender());
    }

    protected boolean hasPermission(CommandSender sender) {
        return parent.hasPermission(sender, this.name);
    }
}
