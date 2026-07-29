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

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;
import org.leavesmc.leaves.entity.bot.actions.CraftUseItemToOffhandAction;

import static org.leavesmc.leaves.bot.agent.actions.ServerUseItemToAction.useItemTo;

public class ServerUseItemToOffhandAction extends AbstractUseBotAction<ServerUseItemToOffhandAction> {

    public ServerUseItemToOffhandAction() {
        super("use_to_offhand", null);
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        EntityHitResult hitResult = bot.getEntityHitResult();
        return useItemTo(bot, hitResult, InteractionHand.OFF_HAND).consumesAction();
    }

    @Override
    public CraftBotAction<?, ServerUseItemToOffhandAction> asCraft() {
        return new CraftUseItemToOffhandAction(this);
    }
}
