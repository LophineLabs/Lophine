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

package org.leavesmc.leaves.protocol.jade.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.ServerDataProvider;

import java.util.List;
import java.util.Objects;

import static org.leavesmc.leaves.protocol.jade.JadeProtocol.entityDataProviders;

public record RequestEntityPayload(EntityAccessor.SyncData data,
                                   List<@Nullable ServerDataProvider<EntityAccessor>> dataProviders) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_REQUEST_ENTITY = JadeProtocol.id("request_entity");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, RequestEntityPayload> CODEC = StreamCodec.composite(
            EntityAccessor.SyncData.STREAM_CODEC,
            RequestEntityPayload::data,
            ByteBufCodecs.<ByteBuf, ServerDataProvider<EntityAccessor>>list()
                    .apply(ByteBufCodecs.idMapper(
                            $ -> Objects.requireNonNull(entityDataProviders.idMapper()).byId($),
                            $ -> Objects.requireNonNull(entityDataProviders.idMapper()).getIdOrThrow($)
                    )),
            RequestEntityPayload::dataProviders,
            RequestEntityPayload::new);
}