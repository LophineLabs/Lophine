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

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Splits large Servux NBT payloads and bounds the lifetime and size of reassembled client data.
 */
public class PacketSplitter {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long STALE_TIMEOUT_MS = 10_000;

    public static final int MAX_TOTAL_PER_PACKET_S2C = 1_048_576;
    public static final int MAX_PAYLOAD_PER_PACKET_S2C = MAX_TOTAL_PER_PACKET_S2C - 5;
    public static final int MAX_TOTAL_PER_PACKET_C2S = 32_767;
    public static final int MAX_PAYLOAD_PER_PACKET_C2S = MAX_TOTAL_PER_PACKET_C2S - 5;
    public static final int DEFAULT_MAX_RECEIVE_SIZE_C2S = 16_777_216;
    public static final int DEFAULT_MAX_RECEIVE_SIZE_S2C = 16_777_216;

    private static final ConcurrentHashMap<Long, ReadingSession> READING_SESSIONS = new ConcurrentHashMap<>(16, 0.9f, 2);
    private static final ScheduledExecutorService CLEANER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Lophine-Servux-PacketSplitter-Cleaner");
        thread.setDaemon(true);
        return thread;
    });

    static {
        CLEANER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            READING_SESSIONS.forEach((key, session) -> {
                if (now - session.lastReceivedTime > STALE_TIMEOUT_MS && READING_SESSIONS.remove(key, session)) {
                    session.release();
                    LOGGER.warn("Evicted stale Servux packet reassembly session {}", key);
                }
            });
        }, 5, 5, TimeUnit.SECONDS);
    }

    public static boolean send(IPacketSplitterHandler handler, FriendlyByteBuf packet, ServerPlayer player) {
        return send(handler, packet, player, MAX_PAYLOAD_PER_PACKET_S2C);
    }

    private static boolean send(IPacketSplitterHandler handler, FriendlyByteBuf packet, ServerPlayer player, int payloadLimit) {
        int length = packet.writerIndex();
        packet.resetReaderIndex();

        for (int offset = 0; offset < length; offset += payloadLimit) {
            int sliceLength = Math.min(length - offset, payloadLimit);
            FriendlyByteBuf slice = new FriendlyByteBuf(Unpooled.buffer(sliceLength));
            if (offset == 0) {
                slice.writeVarInt(length);
            }
            slice.writeBytes(packet, sliceLength);
            handler.encode(player, slice);
        }

        packet.release();
        return true;
    }

    public static FriendlyByteBuf receive(long key, FriendlyByteBuf buf) {
        return receive(key, buf, DEFAULT_MAX_RECEIVE_SIZE_C2S);
    }

    @Nullable
    private static FriendlyByteBuf receive(long key, FriendlyByteBuf buf, int maxLength) {
        ReadingSession session = READING_SESSIONS.computeIfAbsent(key, ReadingSession::new);
        try {
            FriendlyByteBuf full = session.receive(buf, maxLength);
            if (full != null) {
                READING_SESSIONS.remove(key, session);
            }
            return full;
        } catch (RuntimeException exception) {
            if (READING_SESSIONS.remove(key, session)) {
                session.release();
            }
            throw exception;
        }
    }

    public interface IPacketSplitterHandler {
        void encode(ServerPlayer player, FriendlyByteBuf buf);
    }

    private static final class ReadingSession {
        private final long key;
        private int expectedSize = -1;
        private FriendlyByteBuf received;
        private volatile long lastReceivedTime = System.currentTimeMillis();

        private ReadingSession(long key) {
            this.key = key;
        }

        @Nullable
        private synchronized FriendlyByteBuf receive(FriendlyByteBuf data, int maxLength) {
            data.readerIndex(0);
            lastReceivedTime = System.currentTimeMillis();

            if (expectedSize < 0) {
                expectedSize = data.readVarInt();
                if (expectedSize < 0 || expectedSize > maxLength) {
                    throw new IllegalArgumentException("Servux payload size " + expectedSize + " exceeds the limit");
                }
                if (expectedSize > 0 && data.readableBytes() == 0) {
                    throw new IllegalArgumentException("Servux payload header was not followed by data");
                }
                received = new FriendlyByteBuf(Unpooled.buffer(expectedSize));
                if (expectedSize == 0) {
                    if (data.isReadable()) {
                        throw new IllegalArgumentException("Servux empty payload contains trailing data");
                    }
                    return received;
                }
            }

            int remaining = expectedSize - received.writerIndex();
            if (data.readableBytes() > remaining) {
                throw new IllegalArgumentException("Servux payload exceeded its declared size");
            }
            received.writeBytes(data.readBytes(data.readableBytes()));
            return received.writerIndex() == expectedSize ? received : null;
        }

        private synchronized void release() {
            if (received != null && received.refCnt() > 0) {
                received.release();
            }
            received = null;
        }
    }
}
