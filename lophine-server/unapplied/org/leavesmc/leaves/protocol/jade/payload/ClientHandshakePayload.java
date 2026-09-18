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

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;

public record ClientHandshakePayload(String protocolVersion) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_CLIENT_HANDSHAKE = JadeProtocol.id("client_handshake");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, ClientHandshakePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ClientHandshakePayload::protocolVersion, ClientHandshakePayload::new
    );
}