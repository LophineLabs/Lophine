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

package org.leavesmc.leaves.protocol.jade.provider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.accessor.Accessor;

public interface StreamServerDataProvider<T extends Accessor<?>, D> extends ServerDataProvider<T> {

    @Override
    default void appendServerData(CompoundTag data, T accessor) {
        D value = streamData(accessor);
        if (value != null) {
            data.put(getUid().toString(), accessor.encodeAsNbt(streamCodec(), value));
        }
    }

    @Nullable
    D streamData(T accessor);

    StreamCodec<RegistryFriendlyByteBuf, D> streamCodec();
}
