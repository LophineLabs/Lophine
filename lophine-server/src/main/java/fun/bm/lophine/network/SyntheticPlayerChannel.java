/*
 * This file is part of Lophine.
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this file. If not, see <https://www.gnu.org/licenses/>.
 */

package fun.bm.lophine.network;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.NoSuchElementException;

/**
 * Dummy Netty transport for Carpet and Leaves fake players.
 * Plugins such as TAB, GSit, and AuthMe read {@code connection.channel} and
 * {@link org.bukkit.entity.Player#getAddress()} during join and quit.
 */
public final class SyntheticPlayerChannel {
    public static final String SINK_HANDLER = "lophine_synthetic_sink";
    public static final InetSocketAddress LOCAL_ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private static final ChannelHandler SINK = new ChannelOutboundHandlerAdapter() {
        @Override
        public boolean isSharable() {
            return true;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    };

    private SyntheticPlayerChannel() {
    }

    public static EmbeddedChannel open() {
        EmbeddedChannel channel = new LoopbackChannel();
        channel.pipeline().addFirst(SINK_HANDLER, SINK);
        return channel;
    }

    public static void bind(Connection connection) {
        EmbeddedChannel channel = open();
        connection.configurePacketHandler(channel.pipeline());
        connection.channel = channel;
        connection.address = LOCAL_ADDRESS;
        connection.preparing = false;
    }

    public static void close(Connection connection) {
        Channel channel = connection.channel;
        if (!(channel instanceof EmbeddedChannel) || !channel.isOpen()) {
            return;
        }
        try {
            channel.pipeline().remove(connection);
        } catch (NoSuchElementException ignored) {
        }
        channel.close();
    }

    static final class LoopbackChannel extends EmbeddedChannel {
        @Override
        public SocketAddress localAddress() {
            return LOCAL_ADDRESS;
        }

        @Override
        public SocketAddress remoteAddress() {
            return LOCAL_ADDRESS;
        }
    }
}
