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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.BlockAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

public enum HopperLockProvider implements StreamServerDataProvider<BlockAccessor, Boolean> {
    INSTANCE;

    private static final Identifier MC_HOPPER_LOCK = JadeProtocol.mc_id("hopper_lock");

    @Override
    public Boolean streamData(@NotNull BlockAccessor accessor) {
        return !accessor.getBlockState().getValue(BlockStateProperties.ENABLED);
    }

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Boolean> streamCodec() {
        return ByteBufCodecs.BOOL.cast();
    }

    @Override
    public Identifier getUid() {
        return MC_HOPPER_LOCK;
    }

    @Override
    public int getDefaultPriority() {
        return BlockNameProvider.INSTANCE.getDefaultPriority() + 10;
    }
}