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
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;
import org.leavesmc.leaves.entity.bot.actions.CraftJumpAction;

public class ServerJumpAction extends AbstractTimerBotAction<ServerJumpAction> {

    public ServerJumpAction() {
        GuiRootNode guiRootNode = new GuiRootNode("Jump", "Jump", Items.ELYTRA, "jump");
        super("jump", guiRootNode);
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        if (!bot.onGround()) {
            return false;
        } else {
            bot.jumpFromGround();
        }
        return true;
    }

    @Override
    public CraftBotAction<?, ServerJumpAction> asCraft() {
        return new CraftJumpAction(this);
    }
}
