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
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.ExtraData;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.arguments.EnumArgumentType;
import org.leavesmc.leaves.entity.bot.action.MoveAction.MoveDirection;
import org.leavesmc.leaves.entity.bot.actions.CraftMoveAction;
import org.leavesmc.leaves.event.bot.BotActionStopEvent;

public class ServerMoveAction extends AbstractStateBotAction<ServerMoveAction> {
    private MoveDirection direction = MoveDirection.FORWARD;

    public ServerMoveAction() {
        super("move", ServerMoveAction::new);
        this.addArgument("direction", EnumArgumentType.fromEnum(MoveDirection.class));

        this.guiData = new GuiRootNode("Move", "Move", null, "move", false);
        for (MoveDirection direction : MoveDirection.values()) {
            this.guiData.child(new GuiRootNode(direction.name, direction.name, null, "move " + direction.name));
        }
    }

    @Override
    public void loadCommand(@NotNull CommandContext context) {
        this.direction = context.getArgument("direction", MoveDirection.class);
    }

    @Override
    public void stop(@NotNull ServerBot bot, BotActionStopEvent.Reason reason) {
        super.stop(bot, reason);
        switch (direction) {
            case FORWARD, BACKWARD -> bot.zza = 0.0f;
            case LEFT, RIGHT -> bot.xxa = 0.0f;
        }
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        boolean isSneaking = bot.isShiftKeyDown();
        float velocity = isSneaking ? 0.3f : 1.0f;
        switch (direction) {
            case FORWARD -> bot.zza = velocity;
            case BACKWARD -> bot.zza = -velocity;
            case LEFT -> bot.xxa = velocity;
            case RIGHT -> bot.xxa = -velocity;
        }
        return true;
    }

    @Override
    public String getActionDataString(@NotNull ExtraData data) {
        data.add("direction", direction.name);
        return super.getActionDataString(data);
    }

    public MoveDirection getDirection() {
        return direction;
    }

    public void setDirection(MoveDirection direction) {
        this.direction = direction;
    }

    @Override
    public Object asCraft() {
        return new CraftMoveAction(this);
    }
}