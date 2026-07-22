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
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftUseItemAction;

public class ServerUseItemAction extends AbstractUseBotAction<ServerUseItemAction> {

    public ServerUseItemAction() {
        super("use", ServerUseItemAction::new, null);
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        return useItem(bot, InteractionHand.MAIN_HAND).consumesAction();
    }

    public static @NotNull InteractionResult useItem(@NotNull ServerBot bot, InteractionHand hand) {
        bot.updateItemInHand(hand);
        InteractionResult result = bot.gameMode.useItem(bot, bot.level(), bot.getItemInHand(hand), hand);
        if (shouldSwing(result)) {
            bot.swing(hand);
        }
        return result;
    }

    @Override
    public Object asCraft() {
        return new CraftUseItemAction(this);
    }
}
