// SPDX-License-Identifier: LGPL-3.0-only
// Command grammar adapted from gnembon/fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

public final class CarpetLogCommand {
    private CarpetLogCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("log")
            .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandLog))
            .executes(context -> list(context.getSource()))
            .then(Commands.literal("clear")
                .executes(context -> clear(context.getSource(), context.getSource().getTextName(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder))
                    .executes(context -> clear(context.getSource(), StringArgumentType.getString(context, "player"), null))))
            .then(Commands.argument("log name", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(CarpetLoggerProtocol.loggerNames(), builder))
                .executes(context -> change(context.getSource(), context.getSource().getTextName(),
                    StringArgumentType.getString(context, "log name"), null, true))
                .then(Commands.literal("clear").executes(context -> clear(context.getSource(), context.getSource().getTextName(),
                    StringArgumentType.getString(context, "log name"))))
                .then(Commands.argument("option", StringArgumentType.string())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        CarpetLoggerProtocol.loggerNames().contains(StringArgumentType.getString(context, "log name"))
                            ? CarpetLoggerProtocol.loggerOptions(StringArgumentType.getString(context, "log name")).options() : java.util.List.of(), builder))
                    .executes(context -> change(context.getSource(), context.getSource().getTextName(),
                        StringArgumentType.getString(context, "log name"), StringArgumentType.getString(context, "option"), false))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder))
                        .executes(context -> change(context.getSource(), StringArgumentType.getString(context, "player"),
                            StringArgumentType.getString(context, "log name"), StringArgumentType.getString(context, "option"), false))))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        try {
            source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            feedback(source, Component.literal("For players only"));
            return 0;
        }
        var subscriptions = CarpetLoggerProtocol.subscriptions(source.getTextName());
        source.sendSuccess(() -> Component.literal("_____________________").withStyle(ChatFormatting.WHITE), false);
        source.sendSuccess(() -> Component.literal("Available logging options:").withStyle(ChatFormatting.WHITE), false);
        for (String name : new TreeSet<>(CarpetLoggerProtocol.loggerNames())) {
            boolean subscribed = subscriptions.containsKey(name);
            var line = Component.literal("  - " + name + ": ").withStyle(ChatFormatting.WHITE);
            var options = CarpetLoggerProtocol.loggerOptions(name).options();
            if (options.isEmpty()) {
                if (subscribed) {
                    line.append(Component.literal("Subscribed ").withStyle(ChatFormatting.GREEN));
                } else {
                    line.append(button("[Subscribe] ", "/log " + name, ChatFormatting.GRAY, "subscribe to " + name, false));
                }
            } else {
                for (String option : options) {
                    if (subscribed && option.equalsIgnoreCase(subscriptions.get(name))) {
                        line.append(Component.literal("[" + option + "] ").withStyle(ChatFormatting.GREEN));
                    } else {
                        line.append(button("[" + option + "] ", "/log " + name + " " + option,
                            subscribed ? ChatFormatting.WHITE : ChatFormatting.GRAY, "subscribe to " + name + " " + option, false));
                    }
                }
            }
            if (subscribed) line.append(button("[X]", "/log " + name, ChatFormatting.DARK_RED, "Click to unsubscribe", true));
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static Component button(String text, String command, ChatFormatting color, String hover, boolean bold) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(bold)
            .withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static boolean online(CommandSourceStack source, String player) {
        if (source.getServer().getPlayerList().getPlayerByName(player) != null) return true;
        feedback(source, Component.literal("No player specified").withStyle(ChatFormatting.RED));
        return false;
    }

    /** Preserve Carpet's command feedback and admin audit behavior without crossing Folia player owners. */
    private static void feedback(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message.copy(), false);
        if (!source.source.acceptsSuccess() || source.isSilent() || !source.source.shouldInformAdmins() || source.getServer() == null) return;

        var level = source.getLevel();
        boolean notifyAdmins = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SEND_COMMAND_FEEDBACK);
        boolean logAdminCommand = source.source != source.getServer()
            && level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.LOG_ADMIN_COMMANDS)
            && (!org.spigotmc.SpigotConfig.silentCommandBlocks
                || !(source.source instanceof net.minecraft.world.level.BaseCommandBlock.CloseableCommandBlockSource));
        if (!notifyAdmins && !logAdminCommand) return;

        Component adminMessage = Component.translatable("chat.type.admin", source.getDisplayName(), message.copy())
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        var server = source.getServer();
        var recipients = OrgCommandNativeEffects.global(server, () -> {
            List<net.minecraft.server.level.ServerPlayer> players = notifyAdmins
                ? server.getPlayerList().getPlayers().stream()
                    .filter(player -> player.commandSource() != source.source
                        && player.getBukkitEntity().hasPermission("minecraft.admin.command_feedback"))
                    .toList()
                : List.of();
            if (logAdminCommand) server.sendSystemMessage(adminMessage.copy());
            return players;
        });
        var delivered = TisCommandContinuations.then(recipients, players -> {
            var sends = new ArrayList<CompletableFuture<Void>>();
            for (var player : players) sends.add(TisCommandContinuations.owned(player, () -> {
                if (!player.isRemoved()) player.sendSystemMessage(adminMessage.copy());
                return null;
            }));
            return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
        });
        ScarpetNativeWork.record(delivered);
    }

    private static Component commandError(String message) {
        for (String prefix : List.of("Unknown logger: ", "Invalid option: ")) {
            if (message.startsWith(prefix)) {
                return Component.literal(prefix).withStyle(ChatFormatting.RED)
                    .append(Component.literal(message.substring(prefix.length())).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
        }
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    private static int change(CommandSourceStack source, String player, String name, String option, boolean toggle) {
        if (!online(source, player)) return 0;
        if (name.equals("movement") && !TisMovementLogger.canSubscribe(source)) {
            source.sendFailure(Component.literal("Movement logging is disabled or requires higher permission."));
            return 0;
        }
        if (name.equals("lifetime") && option != null && !TisLifetimeTracker.isValidLoggerOption(option)) {
            feedback(source, commandError("Invalid option: " + option));
            return 0;
        }
        try {
            boolean subscribed = true;
            if (toggle) subscribed = CarpetLoggerProtocol.toggle(player, name);
            else CarpetLoggerProtocol.subscribe(player, name, option);
            String message = toggle
                ? player + (subscribed ? " subscribed to " : " unsubscribed from ") + name + "."
                : "Subscribed to " + name + "(" + option + ")";
            feedback(source, Component.literal(message).withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
            return 1;
        } catch (IllegalArgumentException exception) {
            feedback(source, commandError(exception.getMessage()));
            return 0;
        }
    }

    private static int clear(CommandSourceStack source, String player, String name) {
        if (!online(source, player)) return 0;
        try {
            CarpetLoggerProtocol.unsubscribe(player, name);
            feedback(source, Component.literal("Unsubscribed from " + (name == null ? "all logs" : name))
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
            return 1;
        } catch (IllegalArgumentException exception) {
            feedback(source, commandError(exception.getMessage()));
            return 0;
        }
    }
}
