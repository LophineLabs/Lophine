package me.earthme.luminol.commands.config.sub;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.earthme.luminol.commands.config.ConfigCommand;
import me.earthme.luminol.commands.config.ConfigSubcommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

public class ReloadCommand extends ConfigSubcommand {
    public ReloadCommand(ConfigCommand parent) {
        super("reload", parent);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        parent.config.reloadAsync(true).thenAccept(nullValue -> context.getSender().sendMessage(
                Component
                        .text("Reloaded config file!")
                        .color(TextColor.color(0, 255, 0))
        ));
        return true;
    }
}
