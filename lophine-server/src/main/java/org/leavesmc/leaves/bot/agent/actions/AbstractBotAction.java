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

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.LophineLogger;
import fun.bm.lophine.bot.action.gui.GuiRootNode;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.Actions;
import org.leavesmc.leaves.bot.agent.ExtraData;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.WrappedArgument;
import org.leavesmc.leaves.entity.bot.actions.CraftBotAction;
import org.leavesmc.leaves.event.bot.BotActionExecuteEvent;
import org.leavesmc.leaves.event.bot.BotActionStopEvent;
import org.leavesmc.leaves.util.UpdateSuppressionException;

import java.util.*;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
public abstract class AbstractBotAction<E extends AbstractBotAction<E>> {

    private final String name;
    private final Map<Integer, List<Pair<String, WrappedArgument<?>>>> arguments;
    private UUID uuid;
    private int currentFork = 0;

    private int initialTickDelay;
    private int initialTickInterval;
    private int initialNumber;

    private int tickToNext;
    private int numberRemaining;
    private boolean cancel;

    private Consumer<E> onFail;
    private Consumer<E> onSuccess;
    private Consumer<E> onStop;

    private final String guiTitle;
    private final String guiDescription;
    private final Item guiItem;
    private final boolean guiConfirmable;
    private final boolean guiVisible;

    public AbstractBotAction(String name) {
        this(name, name, null, null, true, false);
    }

    public AbstractBotAction(String name, String guiTitle, String guiDescription, Item guiItem) {
        this(name, guiTitle, guiDescription, guiItem, true, true);
    }

    public AbstractBotAction(String name, String guiTitle, String guiDescription, Item guiItem, boolean guiConfirmable) {
        this(name, guiTitle, guiDescription, guiItem, guiConfirmable, true);
    }

    protected AbstractBotAction(String name, String guiTitle, String guiDescription, Item guiItem, boolean guiConfirmable, boolean guiVisible) {
        this.name = name;
        this.guiTitle = guiTitle;
        this.guiDescription = guiDescription;
        this.guiItem = guiItem;
        this.guiConfirmable = guiConfirmable;
        this.guiVisible = guiVisible;
        this.uuid = UUID.randomUUID();
        this.arguments = new HashMap<>();

        this.cancel = false;
        this.setStartDelayTick(0);
        this.setDoIntervalTick(1);
        this.setDoNumber(1);
    }

    public abstract boolean doTick(@NotNull ServerBot bot);

    public abstract CraftBotAction<?, E> asCraft();

    public String getActionDataString() {
        return getActionDataString(new ExtraData(new ArrayList<>()));
    }

    public String getActionDataString(@NotNull ExtraData data) {
        return data.raw().stream()
                .map(pair -> pair.getLeft() + "=" + pair.getRight())
                .reduce((a, b) -> a + ", " + b)
                .orElse("No arguments");
    }

    public void init() {
        this.tickToNext = initialTickDelay;
        this.numberRemaining = this.getDoNumber();
        this.setCancelled(false);
    }

    public void fork(int fork) {
        currentFork = fork;
    }

    public <T> WrappedArgument<T> addArgument(String name, ArgumentType<T> type) {
        WrappedArgument<T> argument = new WrappedArgument<>(name, type);
        this.arguments
                .computeIfAbsent(currentFork, k -> new ArrayList<>())
                .add(Pair.of(name, argument));
        return argument;
    }

    public Map<Integer, List<Pair<String, WrappedArgument<?>>>> getArguments() {
        return this.arguments;
    }

    public void tryTick(ServerBot bot) {
        if (this.numberRemaining == 0) {
            this.stop(bot, BotActionStopEvent.Reason.DONE);
            return;
        }

        if (this.cancel) {
            this.stop(bot, BotActionStopEvent.Reason.PLUGIN);
            return;
        }

        if (this.tickToNext <= 0) {
            BotActionExecuteEvent event = new BotActionExecuteEvent(bot.getBukkitEntity(), name, uuid);

            event.callEvent();
            if (event.getResult() == BotActionExecuteEvent.Result.SOFT_CANCEL) {
                this.tickToNext = this.getDoIntervalTick();
                return;
            } else if (event.getResult() == BotActionExecuteEvent.Result.HARD_CANCEL) {
                if (this.numberRemaining > 0) {
                    this.numberRemaining--;
                }
                this.tickToNext = this.getDoIntervalTick();
                return;
            }

            boolean result = false;
            try {
                result = this.doTick(bot);
            } catch (UpdateSuppressionException e) {
                e.providePlayer(bot);
                e.consume();
            } catch (Exception e) {
                LophineLogger.LOGGER.error("An error occurred while executing bot {}, action {}", bot.displayName, this.name, e);
            }

            if (result) {
                if (this.numberRemaining > 0) {
                    this.numberRemaining--;
                }
                this.tickToNext = this.getDoIntervalTick();
                if (this.onSuccess != null) {
                    this.onSuccess.accept((E) this);
                }
            } else if (this.onFail != null) {
                this.onFail.accept((E) this);
            }
        } else {
            this.tickToNext--;
        }
    }

    @NotNull
    public CompoundTag save(@NotNull CompoundTag nbt) {
        if (!this.cancel) {
            nbt.putString("actionName", this.name);
            nbt.store("actionUUID", UUIDUtil.CODEC, this.uuid);

            nbt.putInt("initialTickDelay", this.initialTickDelay);
            nbt.putInt("initialTickInterval", this.initialTickInterval);
            nbt.putInt("initialNumber", this.initialNumber);

            nbt.putInt("tickToNext", this.tickToNext);
            nbt.putInt("numberRemaining", this.numberRemaining);
        }
        return nbt;
    }

    public void load(@NotNull CompoundTag nbt) {
        this.uuid = nbt.read("actionUUID", UUIDUtil.CODEC).orElse(UUID.randomUUID());

        this.initialTickDelay = nbt.getIntOr("initialTickDelay", 0);
        this.initialTickInterval = nbt.getIntOr("initialTickInterval", 0);
        this.initialNumber = nbt.getIntOr("initialNumber", 0);

        this.tickToNext = nbt.getIntOr("tickToNext", 0);
        this.numberRemaining = nbt.getIntOr("numberRemaining", 0);
    }

    public void stop(@NotNull ServerBot bot, BotActionStopEvent.Reason reason) {
        new BotActionStopEvent(bot.getBukkitEntity(), this.name, this.uuid, reason, null).callEvent();
        this.setCancelled(true);
        if (this.onStop != null) {
            this.onStop.accept((E) this);
        }
    }

    @SuppressWarnings("RedundantThrows")
    public void loadCommand(@NotNull CommandContext context) throws CommandSyntaxException {
    }

    public void loadGui(@NotNull List<String> values) throws CommandSyntaxException {
        List<Pair<String, WrappedArgument<?>>> args = this.arguments.getOrDefault(0, List.of());
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 0; i < values.size() && i < args.size(); i++) {
            named.put(args.get(i).getLeft(), values.get(i));
        }
        this.loadGuiValues(named);
    }

    @SuppressWarnings("RedundantThrows")
    protected void loadGuiValues(@NotNull Map<String, String> values) throws CommandSyntaxException {
    }

    protected static int guiInt(@NotNull Map<String, String> values, String key, int defaultValue) {
        String raw = values.get(key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }

    public String getName() {
        return this.name;
    }

    public UUID getUUID() {
        return uuid;
    }

    public void setStartDelayTick(int initialTickDelay) {
        this.initialTickDelay = initialTickDelay;
    }

    public int getStartDelayTick() {
        return this.initialTickDelay;
    }

    public void setDoIntervalTick(int initialTickInterval) {
        this.initialTickInterval = Math.max(0, initialTickInterval);
    }

    public int getDoIntervalTick() {
        return this.initialTickInterval;
    }

    public void setDoNumber(int initialNumber) {
        this.initialNumber = Math.max(-1, initialNumber);
    }

    public int getDoNumber() {
        return this.initialNumber;
    }

    public int getTickToNext() {
        return this.tickToNext;
    }

    public int getDoNumberRemaining() {
        return this.numberRemaining;
    }

    public boolean isCancelled() {
        return cancel;
    }

    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    public void setOnFail(Consumer<E> onFail) {
        this.onFail = onFail;
    }

    public void setOnSuccess(Consumer<E> onSuccess) {
        this.onSuccess = onSuccess;
    }

    public void setOnStop(Consumer<E> onStop) {
        this.onStop = onStop;
    }

    public String getGuiTitle() {
        return this.guiTitle;
    }

    public String getGuiDescription() {
        return this.guiDescription;
    }

    public Item getGuiItem() {
        return this.guiItem;
    }

    public boolean isGuiConfirmable() {
        return this.guiConfirmable;
    }

    public boolean isGuiVisible() {
        return this.guiVisible;
    }

    public @Nullable GuiRootNode getGuiData() {
        Actions<?> holder = Actions.getByName(this.name);
        return holder == null ? null : holder.getGuiRoot();
    }
}
