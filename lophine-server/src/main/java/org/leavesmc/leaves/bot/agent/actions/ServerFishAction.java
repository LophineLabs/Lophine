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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftFishAction;

public class ServerFishAction extends AbstractTimerBotAction<ServerFishAction> {

    public ServerFishAction() {
        GuiRootNode guiRootNode = new GuiRootNode("Fish", "Fish", null, "fish");
        super("fish", ServerFishAction::new, guiRootNode);
    }

    private static final int CATCH_ENTITY_DELAY = 20;

    private int initialFishInterval = 0;
    private int tickToNextFish = 0;

    @Override
    public void setDoIntervalTick(int initialTickInterval) {
        super.setDoIntervalTick(0);
        this.initialFishInterval = initialTickInterval;
    }

    @Override
    @NotNull
    public CompoundTag save(@NotNull CompoundTag nbt) {
        super.save(nbt);
        nbt.putInt("initialFishInterval", this.initialFishInterval);
        nbt.putInt("tickToNextFish", this.tickToNextFish);
        return nbt;
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);
        this.initialFishInterval = nbt.getIntOr("initialFishInterval", this.initialFishInterval);
        this.tickToNextFish = nbt.getIntOr("tickToNextFish", this.tickToNextFish);
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        if (this.tickToNextFish > 0) {
            this.tickToNextFish--;
            return false;
        }

        ItemStack mainHand = bot.getMainHandItem();
        if (mainHand == ItemStack.EMPTY || mainHand.getItem().getClass() != FishingRodItem.class) {
            return false;
        }

        FishingHook fishingHook = bot.fishing;
        if (fishingHook != null) {
            if (fishingHook.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) {
                mainHand.use(bot.level(), bot, InteractionHand.MAIN_HAND);
                this.tickToNextFish = CATCH_ENTITY_DELAY;
                return false;
            }
            if (fishingHook.nibble > 0) {
                mainHand.use(bot.level(), bot, InteractionHand.MAIN_HAND);
                this.tickToNextFish = this.initialFishInterval - 1;
                return true;
            }
        } else {
            mainHand.use(bot.level(), bot, InteractionHand.MAIN_HAND);
        }

        return false;
    }

    @Override
    public Object asCraft() {
        return new CraftFishAction(this);
    }
}
