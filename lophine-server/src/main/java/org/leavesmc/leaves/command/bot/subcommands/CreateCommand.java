/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.command.bot.subcommands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
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

public class CreateCommand extends BotSubcommand {

    public CreateCommand() {
        super("create");
        children(NameArgument::new);
    }

    protected static boolean handleCreateCommand(@NotNull CommandContext context) throws CommandSyntaxException {
        CommandSender sender = context.getSender();

        String rawName = context.getArgument(NameArgument.class);
        String fullName = BotUtil.getFullName(rawName);
        if (!canCreate(sender, fullName)) { // Check full name
            return false;
        }
        String skinName = context.getArgumentOrDefault(SkinNameArgument.class, rawName); // Use raw name for correct skin

        World world;
        try {
            world = context.getArgument(WorldArgument.class);
        } catch (IllegalArgumentException e) {
            if (!(sender instanceof Entity entity)) {
                sender.sendMessage(text("Must specify world and location when executed by console", NamedTextColor.RED));
                return false;
            }
            world = entity.getWorld();
        }

        Location location = Bukkit.getWorlds().getFirst().getSpawnLocation();
        FinePositionResolver positionResolver = context.getArgumentOrDefault(LocationArgument.class, null);
        if (positionResolver != null) {
            Vector vec3 = positionResolver.resolve(context.getSource()).toVector();
            location = new Location(world, vec3.getX(), vec3.getY(), vec3.getZ());
        } else if (sender instanceof Entity entity) {
            location = entity.getLocation();
        }

        BotCreateState
                .builder(rawName, location)
                .createReason(BotCreateEvent.CreateReason.COMMAND)
                .skinName(skinName)
                .creator(sender)
                .spawnWithSkin(bot -> {
                    if (bot != null) {
                        ServerBot serverBot = ((CraftBot) bot).getHandle();
                        BotList.INSTANCE.loadBotConfigs(serverBot);
                        BotList.INSTANCE.loadBotInventoryAndEquipment(serverBot);
                    }
                });

        return true;
    }

    static boolean canCreate(CommandSender sender, @NotNull String name) {
        BotList botList = BotList.INSTANCE;
        if (!name.matches("^[a-zA-Z0-9_]{4,16}$")) {
            sender.sendMessage(text("This name is illegal, bot name must be 4-16 characters and contain only letters, numbers, and underscores.", NamedTextColor.RED));
            return false;
        }

        if (Bukkit.getPlayerExact(name) != null || botList.getBotByName(name) != null) {
            sender.sendMessage(text("This bot is already in server", NamedTextColor.RED));
            return false;
        }

        if (FakeplayerConfig.unableNames.contains(name)) {
            sender.sendMessage(text("This name is not allowed in this server", NamedTextColor.RED));
            return false;
        }

        if (botList.bots.size() >= FakeplayerConfig.limit) {
            sender.sendMessage(text("Bot number limit exceeded", NamedTextColor.RED));
            return false;
        }

        return true;
    }

    private static class NameArgument extends ArgumentNode<String> {
        private NameArgument() {
            super("name", StringArgumentType.word());
            children(SkinNameArgument::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }

    private static class SkinNameArgument extends ArgumentNode<String> {
        private SkinNameArgument() {
            super("skin_name", StringArgumentType.word());
            children(WorldArgument::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }

    private static class WorldArgument extends ArgumentNode<World> {
        private WorldArgument() {
            super("world", ArgumentTypes.world());
            children(LocationArgument::new);
        }

        @Override
        public boolean requires(@NotNull CommandSourceStack source) {
            return source.getSender() instanceof ConsoleCommandSender;
        }
    }

    private static class LocationArgument extends ArgumentNode<FinePositionResolver> {
        private LocationArgument() {
            super("location", ArgumentTypes.finePosition());
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }
}
