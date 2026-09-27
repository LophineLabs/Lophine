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

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.util.TagUtil;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Powered by Servux(https://github.com/sakura-ryoko/servux)

@LeavesProtocol.Register(namespace = "servux")
public class ServuxEntityDataProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final int PROTOCOL_VERSION = 2;

    private static final Map<UUID, Long> readingSessionKeys = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> registeredPlayers = ConcurrentHashMap.newKeySet();

    @ProtocolHandler.PlayerJoin
    public static void onPlayerJoin(ServerPlayer player) {
        sendMetadata(player);
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        readingSessionKeys.remove(player.getUUID());
        registeredPlayers.remove(player.getUUID());
    }

    @ProtocolHandler.PayloadReceiver(payload = EntityDataPayload.class)
    public static void onPacketReceive(ServerPlayer player, EntityDataPayload payload) {
        switch (payload.packetType) {
            case PACKET_C2S_METADATA_REQUEST -> {
                if (payload.nbt.getIntOr("version", -1) >= PROTOCOL_VERSION && ServuxProtocolConfig.entityProtocol) {
                    registeredPlayers.add(player.getUUID());
                    sendMetadata(player);
                }
            }
            case PACKET_C2S_UNREGISTER_REPLY -> {
                registeredPlayers.remove(player.getUUID());
                readingSessionKeys.remove(player.getUUID());
            }
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                if (isRegistered(player)) onBlockEntityRequest(player, payload.pos);
            }
            case PACKET_C2S_ENTITY_REQUEST -> {
                if (isRegistered(player)) onEntityRequest(player, payload.entityId);
            }
            case PACKET_C2S_NBT_RESPONSE_DATA -> {
                if (!isRegistered(player)) return;
                UUID uuid = player.getUUID();
                long readingSessionKey;

                if (!readingSessionKeys.containsKey(uuid)) {
                    readingSessionKey = RandomSource.create(Util.getMillis()).nextLong();
                    readingSessionKeys.put(uuid, readingSessionKey);
                } else {
                    readingSessionKey = readingSessionKeys.get(uuid);
                }

                FriendlyByteBuf fullPacket = PacketSplitter.receive(readingSessionKey, payload.buffer);

                if (fullPacket != null) {
                    readingSessionKeys.remove(uuid);
                    try {
                        LOGGER.warn("Servux entity data does not accept client-to-server bulk NBT requests from {}", player.getScoreboardName());
                    } finally {
                        fullPacket.release();
                    }
                }
            }
        }
    }

    public static void sendMetadata(ServerPlayer player) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "entity_data");
        metadata.putString("id", EntityDataPayload.CHANNEL.toString());
        metadata.putInt("version", PROTOCOL_VERSION);
        metadata.putString("servux", ServuxProtocol.SERVUX_STRING);

        EntityDataPayload payload = new EntityDataPayload(EntityDataPayloadType.PACKET_S2C_METADATA);
        payload.nbt.merge(metadata);
        sendPacket(player, payload);
    }

    public static void onBlockEntityRequest(ServerPlayer player, BlockPos pos) {
        if (!isRegistered(player) || !ServuxProtocolConfig.entityProtocol) return;
        player.getBukkitEntity().taskScheduler.schedule((LivingEntity nmsEntity) -> {
            if (!TickThread.isTickThreadFor(nmsEntity.level(), pos)) return;
            BlockEntity be = nmsEntity.level().getBlockEntity(pos);
            CompoundTag nbt = be != null ? be.saveWithFullMetadata(nmsEntity.registryAccess()) : new CompoundTag();

            EntityDataPayload payload = new EntityDataPayload(EntityDataPayloadType.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
            payload.pos = pos.immutable();
            payload.nbt.merge(nbt);
            sendPacket((ServerPlayer) nmsEntity, payload);
        }, null, 1L);
    }

    public static void onEntityRequest(ServerPlayer player, int entityId) {
        if (!isRegistered(player) || !ServuxProtocolConfig.entityProtocol) return;
        final Vec3 pos = player.position();
        player.getBukkitEntity().taskScheduler.schedule((LivingEntity nmsEntity) -> {
            if (!TickThread.isTickThreadFor(nmsEntity.level(), pos)) return;
            Entity entity = nmsEntity.level().getEntity(entityId);
            if (entity == null || !TickThread.isTickThreadFor(entity)) return;
            CompoundTag nbt = TagUtil.saveEntityWithoutId(entity);

            if (entity instanceof ServerPlayer target && !target.getUUID().equals(nmsEntity.getUUID())) {
                if (!ServuxProtocolConfig.nbtAllowPlayerInventory
                        || !ServuxProtocol.hasPermissionLevel(player, ServuxProtocolConfig.playerInventoryPermissionLevel)) {
                    nbt.remove("Inventory");
                    nbt.put("Inventory", new ListTag());
                }
                if (!ServuxProtocolConfig.nbtAllowPlayerEnderItems
                        || !ServuxProtocol.hasPermissionLevel(player, ServuxProtocolConfig.playerEnderItemsPermissionLevel)) {
                    nbt.remove("EnderItems");
                    nbt.put("EnderItems", new ListTag());
                }
            }

            EntityDataPayload payload = new EntityDataPayload(EntityDataPayloadType.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE);
            payload.entityId = entityId;
            payload.nbt.merge(nbt);
            sendPacket((ServerPlayer) nmsEntity, payload);
        }, null, 1L);
    }

    public static void sendPacket(ServerPlayer player, EntityDataPayload payload) {
        if (payload.packetType == EntityDataPayloadType.PACKET_S2C_NBT_RESPONSE_START) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            ServuxDataByteBuf.write(buffer, payload.nbt);
            PacketSplitter.send(ServuxEntityDataProtocol::sendWithSplitter, buffer, player);
        } else {
            ProtocolUtils.sendPayloadPacket(player, payload);
        }
    }

    private static boolean isRegistered(ServerPlayer player) {
        return ServuxProtocolConfig.entityProtocol && registeredPlayers.contains(player.getUUID());
    }

    private static void sendWithSplitter(ServerPlayer player, FriendlyByteBuf buf) {
        EntityDataPayload payload = new EntityDataPayload(EntityDataPayloadType.PACKET_S2C_NBT_RESPONSE_DATA);
        payload.buffer = buf;
        payload.nbt = new CompoundTag();
        sendPacket(player, payload);
    }

    @Override
    public boolean isActive() {
        return ServuxProtocolConfig.entityProtocol;
    }

    public enum EntityDataPayloadType {
        PACKET_S2C_METADATA(1),
        PACKET_C2S_METADATA_REQUEST(2),
        PACKET_C2S_BLOCK_ENTITY_REQUEST(3),
        PACKET_C2S_ENTITY_REQUEST(4),
        PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE(5),
        PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE(6),
        PACKET_C2S_UNREGISTER_REPLY(7),
        // For Packet Splitter (Oversize Packets, S2C)
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11),
        // For Packet Splitter (Oversize Packets, C2S)
        PACKET_C2S_NBT_RESPONSE_START(12),
        PACKET_C2S_NBT_RESPONSE_DATA(13);

        public final int type;

        EntityDataPayloadType(int type) {
            this.type = type;
            Helper.ID_TO_TYPE.put(type, this);
        }

        public static EntityDataPayloadType fromId(int id) {
            return Helper.ID_TO_TYPE.get(id);
        }

        private static final class Helper {
            static Map<Integer, EntityDataPayloadType> ID_TO_TYPE = new HashMap<>();
        }
    }

    public static class EntityDataPayload implements LeavesCustomPayload {

        @ID
        public static final Identifier CHANNEL = ServuxProtocol.id("entity_data");

        @Codec
        public static final StreamCodec<FriendlyByteBuf, EntityDataPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.packetType.type);
                    switch (payload.packetType) {
                        case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                            buf.writeBlockPos(payload.pos);
                        }
                        case PACKET_C2S_ENTITY_REQUEST -> {
                            buf.writeVarInt(payload.entityId);
                        }
                        case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                            buf.writeBlockPos(payload.pos);
                            ServuxDataByteBuf.write(buf, payload.nbt);
                        }
                        case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> {
                            buf.writeVarInt(payload.entityId);
                            ServuxDataByteBuf.write(buf, payload.nbt);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY -> ServuxDataByteBuf.write(buf, payload.nbt);
                        case PACKET_S2C_NBT_RESPONSE_DATA, PACKET_C2S_NBT_RESPONSE_DATA ->
                                buf.writeBytes(payload.buffer.copy());
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> buf.writeNbt(payload.nbt);
                    }
                },
                buf -> {
                    EntityDataPayloadType type = EntityDataPayloadType.fromId(buf.readVarInt());
                    if (type == null) {
                        throw new IllegalStateException("invalid packet type received");
                    }
                    EntityDataPayload payload = new EntityDataPayload(type);
                    switch (type) {
                        case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                            payload.pos = buf.readBlockPos().immutable();
                        }
                        case PACKET_C2S_ENTITY_REQUEST -> {
                            payload.entityId = buf.readVarInt();
                        }
                        case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                            payload.pos = buf.readBlockPos().immutable();
                            payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        }
                        case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> {
                            payload.entityId = buf.readVarInt();
                            payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY -> ServuxDataByteBuf.skip(buf);
                        case PACKET_S2C_NBT_RESPONSE_DATA, PACKET_C2S_NBT_RESPONSE_DATA -> {
                            payload.buffer = new FriendlyByteBuf(buf.readBytes(buf.readableBytes()));
                            payload.nbt = new CompoundTag();
                        }
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> {
                            CompoundTag nbt = buf.readNbt();
                            if (nbt != null) {
                                payload.nbt.merge(nbt);
                            }
                        }
                    }
                    return payload;
                }
        );

        private final EntityDataPayloadType packetType;
        private final int transactionId;
        private int entityId;
        private BlockPos pos;
        private CompoundTag nbt;
        private FriendlyByteBuf buffer;

        private EntityDataPayload(EntityDataPayloadType type) {
            this.packetType = type;
            this.transactionId = -1;
            this.entityId = -1;
            this.pos = BlockPos.ZERO;
            this.nbt = new CompoundTag();
            this.clearPacket();
        }

        private void clearPacket() {
            if (this.buffer != null) {
                this.buffer.clear();
                this.buffer = new FriendlyByteBuf(Unpooled.buffer());
            }
        }
    }
}
