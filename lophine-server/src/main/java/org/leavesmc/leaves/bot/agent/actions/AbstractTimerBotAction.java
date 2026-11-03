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

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.agent.ExtraData;
import org.leavesmc.leaves.command.CommandContext;

import java.util.List;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public abstract class AbstractTimerBotAction<E extends AbstractTimerBotAction<E>> extends AbstractBotAction<E> {

    public AbstractTimerBotAction(String name) {
        super(name);
        this.addTimerArguments();
    }

    public AbstractTimerBotAction(String name, String guiTitle, String guiDescription, Item guiItem) {
        super(name, guiTitle, guiDescription, guiItem);
        this.addTimerArguments();
    }

    private void addTimerArguments() {
        this.addArgument("delay", integer(0))
                .guiSuggest("Delay for a few ticks", List.of("0", "5", "10", "20"))
                .setOptional(true);
        this.addArgument("interval", integer(0))
                .guiSuggest("Interval for a few ticks", List.of("20", "0", "5", "10"))
                .setOptional(true);
        this.addArgument("do_number", integer(-1))
                .guiSuggest("-1", "Number of times to do")
                .setOptional(true);
    }

    @Override
    public void loadCommand(@NotNull CommandContext context) {
        this.applyTimer(
                context.getIntegerOrDefault("delay", 0),
                context.getIntegerOrDefault("interval", 20),
                context.getIntegerOrDefault("do_number", 1)
        );
    }

    @Override
    protected void loadGuiValues(@NotNull Map<String, String> values) {
        this.applyTimer(
                guiInt(values, "delay", 0),
                guiInt(values, "interval", 20),
                guiInt(values, "do_number", 1)
        );
    }

    private void applyTimer(int delay, int interval, int doNumber) {
        this.setStartDelayTick(delay);
        this.setDoIntervalTick(interval);
        this.setDoNumber(doNumber);
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
