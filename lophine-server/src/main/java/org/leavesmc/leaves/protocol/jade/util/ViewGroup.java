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

package org.leavesmc.leaves.protocol.jade.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ViewGroup<T> {
    public List<T> views;
    @Nullable
    public String id;
    @Nullable
    protected CompoundTag extraData;

    public ViewGroup(List<T> views) {
        this(views, Optional.empty(), Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public ViewGroup(List<T> views, Optional<String> id, Optional<CompoundTag> extraData) {
        this.views = views;
        this.id = id.orElse(null);
        this.extraData = extraData.orElse(null);
    }

    public static <B extends ByteBuf, T> StreamCodec<B, ViewGroup<T>> codec(StreamCodec<B, T> viewCodec) {
        return StreamCodec.composite(
                ByteBufCodecs.<B, T>list().apply(viewCodec),
                $ -> $.views,
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8),
                $ -> Optional.ofNullable($.id),
                ByteBufCodecs.optional(ByteBufCodecs.COMPOUND_TAG),
                $ -> Optional.ofNullable($.extraData),
                ViewGroup::new);
    }

    public static <B extends ByteBuf, T> StreamCodec<B, Map.Entry<Identifier, List<ViewGroup<T>>>> listCodec(StreamCodec<B, T> viewCodec) {
        return StreamCodec.composite(
                Identifier.STREAM_CODEC,
                Map.Entry::getKey,
                ByteBufCodecs.<B, ViewGroup<T>>list().apply(codec(viewCodec)),
                Map.Entry::getValue,
                Map::entry);
    }

    public CompoundTag getExtraData() {
        if (extraData == null) {
            extraData = new CompoundTag();
        }
        return extraData;
    }
}
