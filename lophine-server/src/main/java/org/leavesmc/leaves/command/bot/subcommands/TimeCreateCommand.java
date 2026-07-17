package org.leavesmc.leaves.command.bot.subcommands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.BotCreateState;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.BotUtil;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.bot.BotSubcommand;
import org.leavesmc.leaves.entity.bot.CraftBot;
import org.leavesmc.leaves.event.bot.BotCreateEvent;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import static net.kyori.adventure.text.Component.text;

public class TimeCreateCommand extends BotSubcommand {

    public TimeCreateCommand() {
        super("time-create");
        children(NameArgument::new);
    }

    private static boolean handleCreate(CommandContext context) throws CommandSyntaxException {
        CommandSender sender = context.getSender();

        if (!(sender instanceof Entity entity)) {
            sender.sendMessage(text("只有玩家才能使用此命令", NamedTextColor.RED));
            return false;
        }

        String rawName = context.getArgument(NameArgument.class);
        String fullName = BotUtil.getFullName(rawName);
        if (!CreateCommand.canCreate(sender, fullName)) {
            return false;
        }

        int seconds = context.getArgumentOrDefault(TimeArgument.class, 0);
        if (seconds < 1 || seconds > 86400) {
            sender.sendMessage(text("时间范围: 1 ~ 86400 秒", NamedTextColor.RED));
            return false;
        }

        Location loc = entity.getLocation();

        sender.sendMessage(
                text("将在 " + seconds + " 秒后在当前位置召唤 " + rawName, NamedTextColor.GREEN)
        );

        Bukkit.getGlobalRegionScheduler().runDelayed(MinecraftInternalPlugin.INSTANCE, task -> {
            if (Bukkit.getPlayerExact(rawName) != null) {
                sender.sendMessage(text("召唤失败: 该玩家已在线或假人已存在", NamedTextColor.RED));
                return;
            }
            if (BotList.INSTANCE.getBotByName(BotUtil.getFullName(rawName)) != null) {
                sender.sendMessage(text("召唤失败: 该假人已存在", NamedTextColor.RED));
                return;
            }
            if (BotList.INSTANCE.bots.size() >= FakeplayerConfig.limit) {
                sender.sendMessage(text("召唤失败: 假人数量已达上限", NamedTextColor.RED));
                return;
            }

            BotCreateState
                    .builder(rawName, loc)
                    .createReason(BotCreateEvent.CreateReason.COMMAND)
                    .skinName(rawName)
                    .creator(sender)
                    .spawnWithSkin(bot -> {
                        if (bot != null) {
                            ServerBot serverBot = ((CraftBot) bot).getHandle();
                            BotList.INSTANCE.loadBotConfigs(serverBot);
                            BotList.INSTANCE.loadBotInventoryAndEquipment(serverBot);
                        }
                    });
        }, seconds * 20L);

        return true;
    }

    private static class NameArgument extends ArgumentNode<String> {
        private NameArgument() {
            super("name", StringArgumentType.word());
            children(TimeArgument::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreate(context);
        }
    }

    private static class TimeArgument extends ArgumentNode<Integer> {
        private TimeArgument() {
            super("seconds", IntegerArgumentType.integer(1, 86400));
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreate(context);
        }
    }
}
