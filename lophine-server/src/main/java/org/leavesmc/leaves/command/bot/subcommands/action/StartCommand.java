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

package org.leavesmc.leaves.command.bot.subcommands.action;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.Actions;
import org.leavesmc.leaves.bot.agent.actions.AbstractBotAction;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

import static io.papermc.paper.adventure.PaperAdventure.asAdventure;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.spaces;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.AQUA;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static org.leavesmc.leaves.command.bot.subcommands.ActionCommand.BotArgument.getBot;

public class StartCommand extends LiteralNode {

    public StartCommand() {
        super("start");
        for (Actions<?> action : Actions.getAll()) {
            GuiRootNode node = action.getGuiRoot();
            node.setExecutor(context -> handleStartCommand(new CommandContext(context), action) ? Command.SINGLE_SUCCESS : 0);
            this.children(node);
        }
    }

    public static boolean start(@NotNull ServerBot bot, @NotNull CommandSender sender, @NotNull Actions<?> holder, @NotNull AbstractBotAction<?> action) {
        if (bot.addBotAction(action, sender)) {
            sender.sendMessage(join(spaces(),
                    text("Action", GRAY),
                    text(holder.getName(), AQUA).hoverEvent(showText(text(action.getActionDataString()))),
                    text("has been issued to", GRAY),
                    asAdventure(bot.getDisplayName())
            ));
        }
        return true;
    }

    private boolean handleStartCommand(CommandContext context, @NotNull Actions<?> holder) throws CommandSyntaxException {
        return start(getBot(context), context.getSender(), holder, holder.createByCommand(context));
    }
}
