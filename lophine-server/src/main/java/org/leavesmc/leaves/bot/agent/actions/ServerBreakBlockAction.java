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
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;
import org.leavesmc.leaves.entity.bot.actions.CraftBreakBlockAction;

public class ServerBreakBlockAction extends AbstractTimerBotAction<ServerBreakBlockAction> {

    public ServerBreakBlockAction() {
        GuiRootNode guiRootNode = new GuiRootNode("Break", "Break a block", Items.DIAMOND_PICKAXE, "break");
        super("break", guiRootNode);
    }

    private ItemStack lastItem = null;
    private BlockPos lastPos = null;
    private int destroyProgressTime = 0;
    private int lastSentState = -1;

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        Block block = bot.getBukkitEntity().getTargetBlockExact(5);
        if (block != null) {
            BlockPos pos = ((CraftBlock) block).getPosition();

            BlockState iblockdata = bot.level().getBlockState(pos);
            if (lastPos == null || !lastPos.equals(pos) || lastItem == null || !lastItem.equals(bot.getMainHandItem())) {
                if (lastPos != null && destroyProgressTime > 0) {
                    bot.level().destroyBlockProgress(bot.getId(), lastPos, -1);
                }
                lastItem = bot.getMainHandItem();
                lastPos = pos;
                destroyProgressTime = 0;
                lastSentState = -1;

                if (!iblockdata.isAir()) {
                    bot.swing(InteractionHand.MAIN_HAND);
                    EnchantmentHelper.onHitBlock(
                            bot.level(), bot.getMainHandItem(), bot, bot, EquipmentSlot.MAINHAND, Vec3.atCenterOf(pos), iblockdata,
                            item -> bot.onEquippedItemBroken(item, EquipmentSlot.MAINHAND)
                    );
                    iblockdata.attack(bot.level(), pos, bot);
                    float f = iblockdata.getDestroyProgress(bot, bot.level(), pos);
                    if (f >= 1.0F) {
                        bot.gameMode.destroyAndAck(pos, 0, "insta mine");
                        bot.updateItemInHand(InteractionHand.MAIN_HAND);
                        finalBreak();
                        return true;
                    } else {
                        destroyProgressTime++;
                        int k = (int) (f * 10.0F);
                        bot.level().destroyBlockProgress(bot.getId(), pos, k);
                        lastSentState = k;
                    }
                }
            } else {
                if (!iblockdata.isAir()) {
                    bot.swing(InteractionHand.MAIN_HAND);
                    float damage = this.incrementDestroyProgress(bot, iblockdata, pos);
                    if (damage >= 1.0F) {
                        bot.gameMode.destroyAndAck(pos, 0, "destroyed");
                        bot.level().destroyBlockProgress(bot.getId(), pos, -1);
                        bot.updateItemInHand(InteractionHand.MAIN_HAND);
                        finalBreak();
                        return true;
                    }
                }
            }
        } else {
            if (lastPos != null) {
                bot.level().destroyBlockProgress(bot.getId(), lastPos, -1);
            }
            finalBreak();
        }
        return false;
    }

    private void finalBreak() {
        lastPos = null;
        lastItem = null;
        destroyProgressTime = 0;
        lastSentState = -1;
    }

    private float incrementDestroyProgress(ServerBot bot, @NotNull BlockState state, BlockPos pos) {
        float f = state.getDestroyProgress(bot, bot.level(), pos) * (float) (++destroyProgressTime);
        int k = (int) (f * 10.0F);

        if (k != lastSentState) {
            bot.level().destroyBlockProgress(bot.getId(), pos, k);
            lastSentState = k;
        }

        return f;
    }

    @Override
    public CraftBotAction<?, ServerBreakBlockAction> asCraft() {
        return new CraftBreakBlockAction(this);
    }
}
