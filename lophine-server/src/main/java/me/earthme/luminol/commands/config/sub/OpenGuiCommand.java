package me.earthme.luminol.commands.config.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.earthme.luminol.commands.config.ConfigCommand;
import me.earthme.luminol.commands.config.ConfigSubcommand;
import me.earthme.luminol.utils.dialog.ConfigCommandDialog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.world.entity.player.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;

import java.util.concurrent.CompletableFuture;

import static org.leavesmc.leaves.command.CommandUtils.getListClosestMatchingLast;

public class OpenGuiCommand extends ConfigSubcommand {
    public OpenGuiCommand(ConfigCommand parent) {
        super("open-gui", parent);
        children(
                new PathArgument(parent)
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        if (context.getSender() instanceof CraftPlayer cPlayer) {
            final Player player = cPlayer.getHandle();
            ConfigCommandDialog.openGui(player, parent.getCommandName(), parent.config);
        } else {
            context.getSender().sendMessage(
                    Component
                            .text("Only player can use this command!")
                            .color(TextColor.color(255, 0, 0))
            );
        }
        return true;
    }

    static class PathArgument extends ArgumentNode<String> {
        protected final ConfigCommand parent;

        PathArgument(ConfigCommand parent) {
            super("path", StringArgumentType.string());
            this.parent = parent;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(PathArgument.class, "");
            int dotIndex = path.lastIndexOf(".");
            builder = builder.createOffset(builder.getInput().lastIndexOf(' ') + dotIndex + 2);
            if (dotIndex == -1) builder.suggest("full");
            for (String s : getListClosestMatchingLast(
                    path.substring(dotIndex + 1),
                    parent.config.completeConfigPath(path)
            )) {
                builder.suggest(s.substring(path.lastIndexOf('.') + 1));
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            if (context.getSender() instanceof CraftPlayer cPlayer) {
                final Player player = cPlayer.getHandle();
                ConfigCommandDialog.openGui(player, parent.getCommandName(), parent.config, context.getArgument(PathArgument.class));
            } else {
                context.getSender().sendMessage(
                        Component
                                .text("Only player can use this command!")
                                .color(TextColor.color(255, 0, 0))
                );
            }
            return true;
        }
    }
}
