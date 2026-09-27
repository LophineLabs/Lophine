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

package org.leavesmc.leaves.protocol.servux;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Contract;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServuxProtocol {

    public static final String PROTOCOL_ID = "servux";
    public static final Logger LOGGER = LoggerFactory.getLogger(PROTOCOL_ID.toUpperCase());
    public static final String SERVUX_STRING = ProtocolUtils.buildProtocolVersion(PROTOCOL_ID);

    public static boolean hasPermissionLevel(ServerPlayer player, int level) {
        int safeLevel = Mth.clamp(level, 0, PermissionLevel.OWNERS.id());
        return player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(safeLevel)));
    }

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.tryBuild(PROTOCOL_ID, path);
    }
}
