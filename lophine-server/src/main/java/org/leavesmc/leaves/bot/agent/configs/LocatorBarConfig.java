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

package org.leavesmc.leaves.bot.agent.configs;

import com.mojang.brigadier.arguments.BoolArgumentType;
import fun.bm.lophine.LophineLogger;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import me.earthme.luminol.config.modules.experiment.CommandConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.waypoints.ServerWaypointManager;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

public class LocatorBarConfig extends AbstractBotConfig<Boolean, LocatorBarConfig> {
    private boolean value;

    public LocatorBarConfig() {
        super("enable_locator_bar", BoolArgumentType.bool(), LocatorBarConfig::new);
        this.value = FakeplayerConfig.enableLocatorBar && CommandConfig.waypointsAndWaypointCommand;
    }

    @Override
    public Boolean getValue() {
        return value;
    }

    @Override
    public void setValue(@NotNull Boolean value) throws IllegalArgumentException {
        setValue(value, 0);
    }

    public void setValue(@NotNull Boolean value, int count) throws IllegalArgumentException {
        if (count > 60) {
            LophineLogger.LOGGER.error("Failed to set locator bar for a fakeplayer after 60 attempts");
            return;
        }
        if (this.bot != null) {
            this.value = value;
            ServerWaypointManager manager = this.bot.level().getWaypointManager(); // Lophine - waypoint for adapt of luminol ver
            if (value) {
                manager.trackWaypoint(this.bot);
            } else {
                manager.untrackWaypoint(this.bot);
            }
        } else {
            Bukkit.getGlobalRegionScheduler().runDelayed(MinecraftInternalPlugin.INSTANCE, (_) -> setValue(value, count + 1), 20);
        }
    }

    @Override
    public Boolean loadFromCommand(@NotNull CommandContext context) {
        return context.getBoolean(getName());
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag nbt) {
        super.save(nbt);
        nbt.putBoolean(getName(), this.getValue());
        return nbt;
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        this.setValue(nbt.getBooleanOr(getName(), FakeplayerConfig.enableLocatorBar && CommandConfig.waypointsAndWaypointCommand));
    }
}