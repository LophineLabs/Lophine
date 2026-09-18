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

package org.leavesmc.leaves.protocol.jade.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.BlockAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

public enum BeehiveProvider implements StreamServerDataProvider<BlockAccessor, Byte> {
    INSTANCE;

    private static final Identifier MC_BEEHIVE = JadeProtocol.mc_id("beehive");

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Byte> streamCodec() {
        return ByteBufCodecs.BYTE.cast();
    }

    @Override
    public Byte streamData(@NotNull BlockAccessor accessor) {
        BeehiveBlockEntity beehive = (BeehiveBlockEntity) accessor.getBlockEntity();
        int bees = beehive.getOccupantCount();
        return (byte) (beehive.isFull() ? bees : -bees);
    }

    @Override
    public Identifier getUid() {
        return MC_BEEHIVE;
    }
}