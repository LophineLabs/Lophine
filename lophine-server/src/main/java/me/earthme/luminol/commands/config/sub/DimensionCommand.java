package me.earthme.luminol.commands.config.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.lophine.utils.ServerI18nUtil;
import me.earthme.luminol.commands.config.ConfigCommand;
import me.earthme.luminol.commands.config.ConfigSubcommand;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.WorldConfigHolder;
import me.earthme.luminol.config.WorldConfigInstance;
import me.earthme.luminol.utils.dialog.ConfigCommandDialog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.leavesmc.leaves.command.CommandUtils.getListClosestMatchingLast;

public class DimensionCommand extends ConfigSubcommand {
    public DimensionCommand(ConfigCommand parent) {
        super("dimension", parent);
        children(new WorldArgument(parent));
    }

    private static ServerLevel levelOf(Identifier worldId) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, worldId);
        return MinecraftServer.getServer().getLevel(key);
    }

    private static @Nullable WorldConfigInstance worldConfig(@NotNull CommandContext context, @NotNull ConfigCommand parent) {
        Identifier worldId = context.getArgument(WorldArgument.class);
        ServerLevel level = levelOf(worldId);
        if (level == null) {
            context.getSender().sendMessage(
                    Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.dimension.missing", worldId.toString()))
                            .color(TextColor.color(255, 0, 0))
            );
            return null;
        }
        WorldConfigHolder holder = level.luminolWorldConfig();
        WorldConfigInstance world = holder == null ? null : holder.get(parent.config.getName());
        if (world == null) {
            holder = ConfigManager.loadWorldConfigs(level);
            level.luminolWorldConfig(holder);
            world = holder.get(parent.config.getName());
        }
        if (world == null) {
            context.getSender().sendMessage(
                    Component.text(ServerI18nUtil.getLocalizedText("general.command.config.dimension.unsupported"))
                            .color(TextColor.color(255, 0, 0))
            );
        }
        return world;
    }

    private static void open(@NotNull CommandContext context, @NotNull ConfigCommand parent, String prefix) {
        if (!(context.getSender() instanceof CraftPlayer cPlayer)) {
            context.getSender().sendMessage(
                    Component.text(ServerI18nUtil.getLocalizedText("general.command.only-player"))
                            .color(TextColor.color(255, 0, 0))
            );
            return;
        }
        Identifier worldId = context.getArgument(WorldArgument.class);
        ServerLevel level = levelOf(worldId);
        if (level == null) {
            context.getSender().sendMessage(
                    Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.dimension.missing", worldId.toString()))
                            .color(TextColor.color(255, 0, 0))
            );
            return;
        }
        ServerPlayer player = cPlayer.getHandle();
        ConfigCommandDialog.openWorldGui(player, parent.getCommandName(), parent.config, level, prefix);
    }

    private static CompletableFuture<Suggestions> buildSuggestionsFuture(@NotNull SuggestionsBuilder builder, String path, ConfigCommand parent) {
        int dotIndex = path.lastIndexOf(".");
        builder = builder.createOffset(builder.getInput().lastIndexOf(' ') + dotIndex + 2);
        for (String s : getListClosestMatchingLast(
                path.substring(dotIndex + 1),
                parent.config.completeWorldConfigPath(path)
        )) {
            builder.suggest(s.substring(path.lastIndexOf('.') + 1));
        }
        return builder.buildFuture();
    }

    static class WorldArgument extends ArgumentNode<Identifier> {
        WorldArgument(ConfigCommand parent) {
            super("world", DimensionArgument.dimension());
            children(
                    new OpenGuiNode(parent),
                    new SetNode(parent),
                    new ResetNode(parent),
                    new SubmitLiteral(parent)
            );
        }
    }

    static class OpenGuiNode extends LiteralNode {
        private final ConfigCommand parent;

        OpenGuiNode(ConfigCommand parent) {
            super("open-gui");
            this.parent = parent;
            children(new PathArgument(parent));
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            open(context, parent, "");
            return true;
        }
    }

    static class PathArgument extends ArgumentNode<String> {
        private final ConfigCommand parent;

        PathArgument(ConfigCommand parent) {
            super("path", StringArgumentType.string());
            this.parent = parent;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(PathArgument.class, "");
            int dotIndex = path.lastIndexOf(".");
            builder = builder.createOffset(builder.getInput().lastIndexOf(' ') + dotIndex + 2);
            if (dotIndex == -1) {
                builder.suggest("full");
            }
            for (String s : getListClosestMatchingLast(
                    path.substring(dotIndex + 1),
                    parent.config.completeWorldConfigPath(path)
            )) {
                builder.suggest(s.substring(path.lastIndexOf('.') + 1));
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            open(context, parent, context.getArgument(PathArgument.class));
            return true;
        }
    }

    static class SetNode extends LiteralNode {
        SetNode(ConfigCommand parent) {
            super("set");
            children(new SetPathArgument(parent));
        }
    }

    static class SetPathArgument extends ArgumentNode<String> {
        private final ConfigCommand parent;

        SetPathArgument(ConfigCommand parent) {
            super("path", StringArgumentType.string());
            this.parent = parent;
            children(new SetValueArgument(parent));
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(SetPathArgument.class, "");
            return buildSuggestionsFuture(builder, path, parent);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String path = context.getArgumentOrDefault(SetPathArgument.class, "");
            CommandSender sender = context.getSender();
            if (!parent.config.isWorldConfigKey(path)) {
                sender.sendMessage(
                        Component.text(ServerI18nUtil.getLocalizedText("general.command.config.set.error.hint"))
                                .color(TextColor.color(255, 0, 0))
                );
                return true;
            }
            WorldConfigInstance world = worldConfig(context, parent);
            if (world == null) {
                return true;
            }
            Object value = world.get(path);
            sender.sendMessage(
                    Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.set", path, String.valueOf(value)))
                            .color(TextColor.color(0, 255, 0))
            );
            return true;
        }
    }

    static class SetValueArgument extends ArgumentNode<String> {
        private final ConfigCommand parent;

        SetValueArgument(ConfigCommand parent) {
            super("value", StringArgumentType.greedyString());
            this.parent = parent;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgument(SetPathArgument.class);
            if (!parent.config.isWorldConfigKey(path)) {
                return builder
                        .suggest(ServerI18nUtil.getLocalizedText("general.command.config.set.error.main"),
                                net.minecraft.network.chat.Component.literal(ServerI18nUtil.getLocalizedText("general.command.config.set.error.hint")))
                        .buildFuture();
            }
            WorldConfigInstance world = worldConfig(context, parent);
            Object value = world == null ? parent.config.getConfigOrigin(path) : world.get(path);
            if (value == null) {
                value = parent.config.getConfigOrigin(path);
            }
            String[] suggestions = parent.config.getConfigSuggestions(path);
            builder.suggest(String.valueOf(value), net.minecraft.network.chat.Component.literal(ServerI18nUtil.getLocalizedText("general.command.config.set.suggest.default"))
                    .withStyle(style -> style.withColor(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GRAY))));
            if (suggestions == null) {
                if (value instanceof Boolean) {
                    builder.suggest(String.valueOf(!(Boolean) value));
                } else if (value instanceof Enum<?> enumValue) {
                    for (Enum<?> constant : enumValue.getClass().getEnumConstants()) {
                        if (constant == value) {
                            continue;
                        }
                        builder.suggest(constant.name());
                    }
                }
            } else {
                for (String suggestion : suggestions) {
                    if (!Objects.equals(suggestion, String.valueOf(value))) {
                        builder.suggest(suggestion);
                    }
                }
            }
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String path = context.getArgument(SetPathArgument.class);
            String value = context.getArgument(SetValueArgument.class);
            CommandSender sender = context.getSender();
            WorldConfigInstance world = worldConfig(context, parent);
            if (world == null) {
                return true;
            }
            if (world.set(path, value)) {
                sender.sendMessage(
                        Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.set.success", path, value))
                                .color(TextColor.color(0, 255, 0))
                );
            } else {
                sender.sendMessage(
                        Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.set.fail", path, value))
                                .color(TextColor.color(255, 0, 0))
                );
            }
            return true;
        }
    }

    static class ResetNode extends LiteralNode {
        ResetNode(ConfigCommand parent) {
            super("reset");
            children(new ResetPathArgument(parent));
        }
    }

    static class ResetPathArgument extends ArgumentNode<String> {
        private final ConfigCommand parent;

        ResetPathArgument(ConfigCommand parent) {
            super("path", StringArgumentType.string());
            this.parent = parent;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(ResetPathArgument.class, "");
            return buildSuggestionsFuture(builder, path, parent);
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String path = context.getArgumentOrDefault(ResetPathArgument.class, "");
            CommandSender sender = context.getSender();
            if (!parent.config.isWorldConfigKey(path)) {
                sender.sendMessage(
                        Component.text(ServerI18nUtil.getLocalizedText("general.command.config.set.error.hint"))
                                .color(TextColor.color(255, 0, 0))
                );
                return true;
            }
            WorldConfigInstance world = worldConfig(context, parent);
            if (world == null) {
                return true;
            }
            world.reset(path);
            sender.sendMessage(
                    Component.text(ServerI18nUtil.getFormatedLocalizedText("general.command.config.reset.value", path, String.valueOf(world.get(path))))
                            .color(TextColor.color(0, 255, 0))
            );
            return true;
        }
    }

    static class SubmitLiteral extends LiteralNode {
        SubmitLiteral(ConfigCommand parent) {
            super("submit");
            children(new SubmitPayloadArgument(parent));
        }
    }

    static class SubmitPayloadArgument extends ArgumentNode<String> {
        private final ConfigCommand parent;

        SubmitPayloadArgument(ConfigCommand parent) {
            super("payload", StringArgumentType.greedyString());
            this.parent = parent;
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            Identifier worldId = context.getArgument(WorldArgument.class);
            ServerLevel level = levelOf(worldId);
            ConfigCommandDialog.processSubmitWorld(
                    context.getSender(),
                    parent.config,
                    level,
                    context.getArgument(SubmitPayloadArgument.class)
            );
            return true;
        }
    }
}
