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

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;
import org.leavesmc.leaves.entity.bot.actions.CraftUseItemOnAction;

public class ServerUseItemOnAction extends AbstractUseBotAction<ServerUseItemOnAction> {

    public ServerUseItemOnAction() {
        super("use_on", null);
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        BlockHitResult hitResult = bot.getBlockHitResult();
        return useItemOn(bot, hitResult, InteractionHand.MAIN_HAND).consumesAction();
    }

    public static InteractionResult useItemOn(ServerBot bot, BlockHitResult hitResult, InteractionHand hand) {
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            return InteractionResult.FAIL;
        }

        BlockPos blockPos = hitResult.getBlockPos();
        if (!bot.level().getWorldBorder().isWithinBounds(blockPos)) {
            return InteractionResult.FAIL;
        }

        bot.updateItemInHand(hand);
        InteractionResult interactionResult = bot.gameMode.useItemOn(bot, bot.level(), bot.getItemInHand(hand), hand, hitResult);
        if (shouldSwing(interactionResult)) {
            bot.swing(hand);
        }

        return interactionResult;
    }

    @Override
    public CraftBotAction<?, ServerUseItemOnAction> asCraft() {
        return new CraftUseItemOnAction(this);
    }
}
