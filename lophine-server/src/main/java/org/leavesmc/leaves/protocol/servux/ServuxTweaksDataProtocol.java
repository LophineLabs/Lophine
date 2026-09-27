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
import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.util.TagUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servux 26.2 tweaks_data support used by Tweakeroo inventory previews.
 */
@LeavesProtocol.Register(namespace = "servux")
public class ServuxTweaksDataProtocol implements LeavesProtocol {
    public static final int PROTOCOL_VERSION = 2;
    private static final Set<UUID> registeredPlayers = ConcurrentHashMap.newKeySet();

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        registeredPlayers.remove(player.getUUID());
    }

    @ProtocolHandler.PayloadReceiver(payload = TweaksDataPayload.class)
    public static void onPacketReceive(ServerPlayer player, TweaksDataPayload payload) {
        switch (payload.packetType) {
            case PACKET_C2S_METADATA_REQUEST -> {
                if (payload.nbt.getIntOr("version", -1) >= PROTOCOL_VERSION
                        && hasPermission(player)) {
                    registeredPlayers.add(player.getUUID());
                    sendMetadata(player);
                }
            }
            case PACKET_C2S_UNREGISTER_REPLY -> registeredPlayers.remove(player.getUUID());
            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                if (isRegistered(player)) onBlockEntityRequest(player, payload.pos);
            }
            case PACKET_C2S_ENTITY_REQUEST -> {
                if (isRegistered(player)) onEntityRequest(player, payload.entityId);
            }
            case PACKET_C2S_NBT_RESPONSE_DATA -> {
                if (payload.buffer != null && payload.buffer.refCnt() > 0) {
                    payload.buffer.release();
                }
            }
            default -> {
            }
        }
    }

    private static boolean hasPermission(ServerPlayer player) {
        return ServuxProtocolConfig.tweaksDataProtocol
                && ServuxProtocol.hasPermissionLevel(player, ServuxProtocolConfig.tweaksPermissionLevel);
    }

    private static boolean isRegistered(ServerPlayer player) {
        return hasPermission(player) && registeredPlayers.contains(player.getUUID());
    }

    private static void sendMetadata(ServerPlayer player) {
        CompoundTag metadata = new CompoundTag();
        metadata.putString("name", "tweaks_data");
        metadata.putString("id", TweaksDataPayload.CHANNEL.toString());
        metadata.putInt("version", PROTOCOL_VERSION);
        metadata.putString("servux", ServuxProtocol.SERVUX_STRING);
        sendPacket(player, new TweaksDataPayload(TweaksDataPayloadType.PACKET_S2C_METADATA, metadata));
    }

    private static void onBlockEntityRequest(ServerPlayer player, BlockPos pos) {
        player.getBukkitEntity().taskScheduler.schedule((LivingEntity taskOwner) -> {
            if (!TickThread.isTickThreadFor(taskOwner.level(), pos)) return;
            BlockEntity blockEntity = taskOwner.level().getBlockEntity(pos);
            if (blockEntity == null) return;

            CompoundTag nbt = blockEntity.saveWithFullMetadata(taskOwner.registryAccess());
            sendPacket(player, new TweaksDataPayload(TweaksDataPayloadType.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE, pos, nbt));
        }, null, 1L);
    }

    private static void onEntityRequest(ServerPlayer player, int entityId) {
        Vec3 position = player.position();
        player.getBukkitEntity().taskScheduler.schedule((LivingEntity taskOwner) -> {
            if (!TickThread.isTickThreadFor(taskOwner.level(), position)) return;
            Entity entity = taskOwner.level().getEntity(entityId);
            if (entity == null || !TickThread.isTickThreadFor(entity)) return;

            CompoundTag nbt = TagUtil.saveEntityWithoutId(entity);
            Identifier entityType = EntityType.getKey(entity.getType());
            if (entity instanceof ServerPlayer target && !target.getUUID().equals(player.getUUID())) {
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
            nbt.putString("id", entityType.toString());
            sendPacket(player, new TweaksDataPayload(TweaksDataPayloadType.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE, entityId, nbt));
        }, null, 1L);
    }

    private static void sendPacket(ServerPlayer player, TweaksDataPayload payload) {
        ProtocolUtils.sendPayloadPacket(player, payload);
    }

    @Override
    public boolean isActive() {
        return ServuxProtocolConfig.tweaksDataProtocol;
    }

    public enum TweaksDataPayloadType {
        PACKET_S2C_METADATA(1),
        PACKET_C2S_METADATA_REQUEST(2),
        PACKET_C2S_BLOCK_ENTITY_REQUEST(3),
        PACKET_C2S_ENTITY_REQUEST(4),
        PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE(5),
        PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE(6),
        PACKET_C2S_UNREGISTER_REPLY(7),
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11),
        PACKET_C2S_NBT_RESPONSE_START(12),
        PACKET_C2S_NBT_RESPONSE_DATA(13);

        private final int type;

        TweaksDataPayloadType(int type) {
            this.type = type;
            Helper.ID_TO_TYPE.put(type, this);
        }

        private static @Nullable TweaksDataPayloadType fromId(int id) {
            return Helper.ID_TO_TYPE.get(id);
        }

        private static final class Helper {
            private static final Map<Integer, TweaksDataPayloadType> ID_TO_TYPE = new HashMap<>();
        }
    }

    public static class TweaksDataPayload implements LeavesCustomPayload {
        @ID
        public static final Identifier CHANNEL = ServuxProtocol.id("tweaks_data");

        @Codec
        public static final StreamCodec<FriendlyByteBuf, TweaksDataPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.packetType.type);
                    switch (payload.packetType) {
                        case PACKET_C2S_BLOCK_ENTITY_REQUEST -> buf.writeBlockPos(payload.pos);
                        case PACKET_C2S_ENTITY_REQUEST -> buf.writeVarInt(payload.entityId);
                        case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                            buf.writeBlockPos(payload.pos);
                            ServuxDataByteBuf.write(buf, payload.nbt);
                        }
                        case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> {
                            buf.writeVarInt(payload.entityId);
                            ServuxDataByteBuf.write(buf, payload.nbt);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY,
                             PACKET_C2S_NBT_RESPONSE_START,
                             PACKET_S2C_NBT_RESPONSE_START -> ServuxDataByteBuf.write(buf, payload.nbt);
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> buf.writeNbt(payload.nbt);
                        case PACKET_C2S_NBT_RESPONSE_DATA, PACKET_S2C_NBT_RESPONSE_DATA -> {
                            if (payload.buffer != null) buf.writeBytes(payload.buffer.copy());
                        }
                    }
                },
                buf -> {
                    TweaksDataPayloadType type = TweaksDataPayloadType.fromId(buf.readVarInt());
                    if (type == null) throw new IllegalStateException("Invalid Servux tweaks_data packet type");
                    TweaksDataPayload payload = new TweaksDataPayload(type);
                    switch (type) {
                        case PACKET_C2S_BLOCK_ENTITY_REQUEST -> payload.pos = buf.readBlockPos().immutable();
                        case PACKET_C2S_ENTITY_REQUEST -> payload.entityId = buf.readVarInt();
                        case PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                            payload.pos = buf.readBlockPos().immutable();
                            payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        }
                        case PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE -> {
                            payload.entityId = buf.readVarInt();
                            payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY -> ServuxDataByteBuf.skip(buf);
                        case PACKET_C2S_NBT_RESPONSE_START,
                             PACKET_S2C_NBT_RESPONSE_START ->
                                payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        case PACKET_C2S_NBT_RESPONSE_DATA, PACKET_S2C_NBT_RESPONSE_DATA ->
                                payload.buffer = new FriendlyByteBuf(buf.readBytes(buf.readableBytes()));
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> {
                            CompoundTag nbt = buf.readNbt();
                            if (nbt != null) payload.nbt = nbt;
                        }
                    }
                    return payload;
                }
        );

        private final TweaksDataPayloadType packetType;
        private int entityId = -1;
        private BlockPos pos = BlockPos.ZERO;
        private CompoundTag nbt = new CompoundTag();
        @Nullable
        private FriendlyByteBuf buffer;

        private TweaksDataPayload(TweaksDataPayloadType type) {
            this.packetType = type;
        }

        private TweaksDataPayload(TweaksDataPayloadType type, CompoundTag nbt) {
            this(type);
            this.nbt = nbt;
        }

        private TweaksDataPayload(TweaksDataPayloadType type, BlockPos pos, CompoundTag nbt) {
            this(type, nbt);
            this.pos = pos.immutable();
        }

        private TweaksDataPayload(TweaksDataPayloadType type, int entityId, CompoundTag nbt) {
            this(type, nbt);
            this.entityId = entityId;
        }
    }
}
