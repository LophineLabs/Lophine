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

package org.leavesmc.leaves.bot.agent.actions;

import fun.bm.lophine.bot.action.gui.GuiRootNode;
import fun.bm.lophine.bot.action.gui.GuiSubNode;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.agent.ExtraData;
import org.leavesmc.leaves.command.CommandContext;

import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static org.leavesmc.leaves.command.ArgumentNode.ArgumentSuggestions.strings;

public abstract class AbstractTimerBotAction<E extends AbstractTimerBotAction<E>> extends AbstractBotAction<E> {

    public AbstractTimerBotAction(String name, Supplier<E> creator, GuiRootNode guiData) {
        super(name, creator);
        String[] delaySuggestions = {"0", "5", "10", "20"};
        String[] intervalSuggestions = {"20", "0", "5", "10"};
        this.addArgument("delay", integer(0)).suggests(strings(delaySuggestions)).setOptional(true);
        this.addArgument("interval", integer(0)).suggests(strings(intervalSuggestions)).setOptional(true);
        this.addArgument("do_number", integer(-1))
                .suggests(((context, builder) -> builder.suggest("-1", Component.literal("do infinite times"))))
                .setOptional(true);

        if (guiData == null) return;

        this.guiData = guiData;
        GuiSubNode[] node1 = new GuiSubNode[delaySuggestions.length];
        for (int i = 0; i < delaySuggestions.length; i++) {
            node1[i] = new GuiSubNode(delaySuggestions[i], "Delay for a few ticks", null, guiData, delaySuggestions[i]);
            guiData.child(node1[i]);
        }
        GuiSubNode[] node2 = new GuiSubNode[delaySuggestions.length * intervalSuggestions.length];
        for (int i = 0; i < delaySuggestions.length; i++) {
            for (int j = 0; j < intervalSuggestions.length; j++) {
                int id = i * intervalSuggestions.length + j;
                node2[id] = new GuiSubNode(intervalSuggestions[j], "Interval for a few ticks", null, node1[i], intervalSuggestions[j]);
                node1[i].child(node2[id]);
            }
        }

        for (GuiSubNode node : node2) {
            GuiSubNode subNode = new GuiSubNode("-1", "Number of times to do", null, node, "-1");
            node.child(subNode);
        }
    }

    @Override
    public void loadCommand(@NotNull CommandContext context) {
        this.setStartDelayTick(context.getIntegerOrDefault("delay", 0));
        this.setDoIntervalTick(context.getIntegerOrDefault("interval", 20));
        this.setDoNumber(context.getIntegerOrDefault("do_number", 1));
    }

    @Override
    public String getActionDataString(@NotNull ExtraData data) {
        data.add("delay", String.valueOf(this.getStartDelayTick()));
        data.add("interval", String.valueOf(this.getDoIntervalTick()));
        data.add("do_number", String.valueOf(this.getDoNumber()));
        data.add("remaining_do_number", String.valueOf(this.getDoNumberRemaining()));
        return super.getActionDataString(data);
    }
}
