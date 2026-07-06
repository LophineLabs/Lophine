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
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftVehicle;
import org.bukkit.entity.Vehicle;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.actions.CraftMountAction;

import java.util.Comparator;
import java.util.List;

public class ServerMountAction extends AbstractBotAction<ServerMountAction> {

    public ServerMountAction() {
        super("mount", ServerMountAction::new);

        this.guiData = new GuiRootNode("Mount", "Mount", null, "mount");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        Location center = bot.getBukkitEntity().getLocation();
        List<Vehicle> vehicles = center.getNearbyEntitiesByType(
                Vehicle.class,
                bot.entityInteractionRange()
        ).stream().sorted(Comparator.comparingDouble(
                (vehicle) -> center.distanceSquared(vehicle.getLocation())
        )).toList();

        for (Vehicle vehicle : vehicles) {
            CraftVehicle craftVehicle = (CraftVehicle) vehicle;
            if (!bot.hasLineOfSight(craftVehicle.getHandle())) {
                continue;
            }
            if (bot.startRiding(craftVehicle.getHandle())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Object asCraft() {
        return new CraftMountAction(this);
    }
}
