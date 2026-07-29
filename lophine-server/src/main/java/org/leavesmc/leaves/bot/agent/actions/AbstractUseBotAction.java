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

import fun.bm.lophine.bot.action.gui.GuiNode;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import fun.bm.lophine.bot.action.gui.GuiSubNode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.ExtraData;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.event.bot.BotActionStopEvent;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public abstract class AbstractUseBotAction<T extends AbstractUseBotAction<T>> extends AbstractTimerBotAction<T> {
    private int useTickTimeout = -1;
    private int alreadyUsedTick = 0;
    private int useItemRemainingTicks = 0;

    public AbstractUseBotAction(String name, GuiRootNode guiData) {
        super(name, guiData);
        this.addArgument("use_timeout", integer(-1))
                .suggests((context, builder) -> {
                    builder.suggest("-1", Component.literal("no use timeout"));
                    builder.suggest("3", Component.literal("minimum bow shoot time"));
                    builder.suggest("10", Component.literal("minimum trident shoot time"));
                })
                .setOptional(true);
        if (guiData == null) return;

        for (GuiNode node : guiData.getAllFurthestChildren()) {
            GuiSubNode node0 = (GuiSubNode) node;
            node0.child(
                    new GuiSubNode("-1", "no use timeout", null, node0, "-1"),
                    new GuiSubNode("3", "minimum bow shoot time", null, node0, "3"),
                    new GuiSubNode("10", "minimum trident shoot time", null, node0, "10")
            );
        }
    }

    @Override
    public void loadCommand(@NotNull CommandContext context) {
        super.loadCommand(context);
        this.useTickTimeout = context.getIntegerOrDefault("use_timeout", -1);
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        if (shouldStartUseItem()) {
            boolean isSuccess = interact(bot);
            syncUseItemRemainingTicks(bot);
            if (alreadyUseOver()) {
                resetAlreadyUsedTick();
                return isSuccess;
            }
        } else {
            syncUseItemRemainingTicks(bot);
        }

        if (alreadyUseOver()) {
            resetAlreadyUsedTick();
            bot.completeUsingItem();
            return true;
        } else {
            increaseAlreadyUsedTick();
            if (isUseTickLimitExceeded()) {
                resetAlreadyUsedTick();
                shouldStartUseItemNextTick();
                return bot.releaseUsingItemWithResult();
            }
            return false;
        }
    }

    protected abstract boolean interact(ServerBot bot);

    public static boolean shouldSwing(InteractionResult result) {
        return result instanceof InteractionResult.Success success && success.swingSource() != InteractionResult.SwingSource.NONE;
    }

    private boolean shouldStartUseItem() {
        return useItemRemainingTicks == 0;
    }

    private boolean alreadyUseOver() {
        return useItemRemainingTicks == 0;
    }

    private boolean isUseTickLimitExceeded() {
        int useTickLimit = useTickTimeout == -1 ? Integer.MAX_VALUE : useTickTimeout;
        return alreadyUsedTick > useTickLimit;
    }

    private void shouldStartUseItemNextTick() {
        this.useItemRemainingTicks = 0;
    }

    private void resetAlreadyUsedTick() {
        this.alreadyUsedTick = 0;
    }

    private void syncUseItemRemainingTicks(@NotNull ServerBot bot) {
        this.useItemRemainingTicks = bot.getUseItemRemainingTicks();
    }

    private void increaseAlreadyUsedTick() {
        this.alreadyUsedTick++;
    }

    @Override
    public String getActionDataString(@NotNull ExtraData data) {
        data.add("use_timeout", String.valueOf(this.useTickTimeout));
        data.add("already_used_tick", String.valueOf(this.alreadyUsedTick));
        return super.getActionDataString(data);
    }

    @Override
    @NotNull
    public CompoundTag save(@NotNull CompoundTag nbt) {
        super.save(nbt);
        nbt.putInt("useTick", this.useTickTimeout);
        nbt.putInt("alreadyUsedTick", this.alreadyUsedTick);
        return nbt;
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);
        this.useTickTimeout = nbt.getIntOr("useTick", this.useTickTimeout);
        this.alreadyUsedTick = nbt.getInt("alreadyUsedTick").orElseGet(
                () -> nbt.getInt("tickToRelease").map(tickToRelease -> this.useTickTimeout - tickToRelease).orElse(this.alreadyUsedTick)
        );
    }

    public int getUseTickTimeout() {
        return useTickTimeout;
    }

    public void setUseTickTimeout(int useTickTimeout) {
        this.useTickTimeout = useTickTimeout;
    }

    @Override
    public void stop(@NotNull ServerBot bot, BotActionStopEvent.Reason reason) {
        super.stop(bot, reason);
        bot.releaseUsingItem();
    }
}
