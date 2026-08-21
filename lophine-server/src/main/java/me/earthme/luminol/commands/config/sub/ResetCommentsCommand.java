package me.earthme.luminol.commands.config.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.earthme.luminol.commands.config.ConfigCommand;
import me.earthme.luminol.commands.config.ConfigSubcommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;

import java.util.concurrent.CompletableFuture;

public class ResetCommentsCommand extends ConfigSubcommand {
    public ResetCommentsCommand(ConfigCommand parent) {
        super("reset-comments", parent);
        children(
                new PathArgument(parent)
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        context.getSender().sendMessage(
                Component
                        .text("If you want to reset comments to default in the configuration file, please use /" + parent.getCommandName() + " reset-comments confirm")
                        .color(TextColor.color(255, 0, 0))
        );
        return true;
    }

    static class PathArgument extends ArgumentNode<String> {
        protected final ConfigCommand parent;

        PathArgument(ConfigCommand parent) {
            super("confirm", StringArgumentType.string());
            this.parent = parent;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            builder.suggest("confirm");
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            String confirm = context.getArgument(CleanCommand.PathArgument.class);
            if (!confirm.equals("confirm")) {
                context.getSender().sendMessage(
                        Component
                                .text("Please use /" + parent.getCommandName() + " reset-comments confirm to confirm!")
                                .color(TextColor.color(255, 0, 0))
                );
                return true;
            }
            parent.config.reloadAsync(false).thenAccept(_ -> context.getSender().sendMessage(
                    Component
                            .text("Reset comments to default in the configuration file!")
                            .color(TextColor.color(0, 255, 0))
            ));
            return true;
        }
    }
}
