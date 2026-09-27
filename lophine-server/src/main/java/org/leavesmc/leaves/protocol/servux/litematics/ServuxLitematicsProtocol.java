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

package org.leavesmc.leaves.protocol.servux.litematics;

import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.protocol.servux.PacketSplitter;
import org.leavesmc.leaves.protocol.servux.ServuxDataByteBuf;
import org.leavesmc.leaves.protocol.servux.ServuxProtocol;
import org.leavesmc.leaves.protocol.servux.litematics.placement.SchematicPlacement;
import org.leavesmc.leaves.protocol.servux.litematics.utils.NbtUtils;
import org.leavesmc.leaves.protocol.servux.litematics.utils.ReplaceBehavior;
import org.leavesmc.leaves.util.TagUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@LeavesProtocol.Register(namespace = "servux")
public class ServuxLitematicsProtocol implements LeavesProtocol {

    public static final int PROTOCOL_VERSION = 2;

    private static final CompoundTag metadata = new CompoundTag();
    private static final Map<UUID, Long> playerSession = new ConcurrentHashMap<>();
    private static final Set<UUID> registeredPlayers = ConcurrentHashMap.newKeySet();

    @ProtocolHandler.Init
    public static void init() {
        metadata.putString("name", "litematic_data");
        metadata.putString("id", ServuxLitematicaPayload.CHANNEL.toString());
        metadata.putInt("version", PROTOCOL_VERSION);
        metadata.putString("servux", ServuxProtocol.SERVUX_STRING);
    }

    public static boolean hasPermission(ServerPlayer player) {
        return player.getBukkitEntity().hasPermission("leaves.protocol.litematics");
    }

    public static void sendMetaData(ServerPlayer player) {
        ServuxLitematicaPayload send = new ServuxLitematicaPayload(ServuxLitematicaPayloadType.PACKET_S2C_METADATA);
        send.nbt.merge(metadata);
        encodeServerData(player, send);
    }

    public static void encodeServerData(ServerPlayer player, @NotNull ServuxLitematicaPayload packet) {
        if (packet.packetType.equals(ServuxLitematicaPayloadType.PACKET_S2C_NBT_RESPONSE_START)) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            ServuxDataByteBuf.write(buffer, packet.getCompound());
            PacketSplitter.send(ServuxLitematicsProtocol::sendWithSplitter, buffer, player);
        } else {
            ProtocolUtils.sendPayloadPacket(player, packet);
        }
    }

    private static void sendWithSplitter(ServerPlayer player, FriendlyByteBuf buf) {
        ServuxLitematicaPayload payload = new ServuxLitematicaPayload(ServuxLitematicaPayloadType.PACKET_S2C_NBT_RESPONSE_DATA);
        payload.buffer = buf;
        payload.nbt = new CompoundTag();
        encodeServerData(player, payload);
    }

    @ProtocolHandler.PlayerJoin
    public static void onPlayerJoin(ServerPlayer player) {
        sendMetaData(player);
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        registeredPlayers.remove(player.getUUID());
        playerSession.remove(player.getUUID());
        ServuxLitematicaTasks.onPlayerLeave(player);
    }

    @ProtocolHandler.PayloadReceiver(payload = ServuxLitematicaPayload.class)
    public static void onPacketReceive(ServerPlayer player, ServuxLitematicaPayload payload) {
        if (!hasPermission(player)) {
            return;
        }

        switch (payload.packetType) {
            case PACKET_C2S_METADATA_REQUEST -> {
                if (payload.nbt.getIntOr("version", -1) >= PROTOCOL_VERSION && ServuxProtocolConfig.litematicsEnabled) {
                    registeredPlayers.add(player.getUUID());
                    sendMetaData(player);
                }
            }
            case PACKET_C2S_UNREGISTER_REPLY -> {
                registeredPlayers.remove(player.getUUID());
                playerSession.remove(player.getUUID());
            }

            case PACKET_C2S_BLOCK_ENTITY_REQUEST -> {
                if (isRegistered(player)) onBlockEntityRequest(player, payload.getPos());
            }

            case PACKET_C2S_ENTITY_REQUEST -> {
                if (isRegistered(player)) onEntityRequest(player, payload.getEntityId());
            }

            case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> {
                if (isRegistered(player)) onBulkEntityRequest(player, payload.getChunkPos(), payload.getCompound());
            }

            case PACKET_C2S_NBT_RESPONSE_DATA -> {
                if (!isRegistered(player)) return;
                ServuxProtocol.LOGGER.debug("nbt response data");
                UUID uuid = player.getUUID();
                Long session = playerSession.getOrDefault(uuid, new Random().nextLong());
                playerSession.put(uuid, session);
                FriendlyByteBuf fullPacket = PacketSplitter.receive(session, payload.getBuffer());
                if (fullPacket == null) {
                    ServuxProtocol.LOGGER.debug("packet is none");
                    return;
                }
                playerSession.remove(uuid);
                try {
                    CompoundTag tag = ServuxDataByteBuf.read(fullPacket, ServuxProtocolConfig.litematicsMaxNbtSize);
                    handleClientPasteRequest(player, tag);
                } catch (RuntimeException exception) {
                    ServuxProtocol.LOGGER.warn("Rejected invalid Servux Litematica data from {}", player.getScoreboardName(), exception);
                } finally {
                    fullPacket.release();
                }
            }
            case PACKET_C2S_TASK_REQUEST -> {
                if (isRegistered(player)) ServuxLitematicaTasks.handleRequest(player, payload.nbt);
            }
            case PACKET_C2S_TASK_CANCEL -> {
                if (isRegistered(player)) ServuxLitematicaTasks.cancel(player);
            }
        }
    }

    private static boolean isRegistered(ServerPlayer player) {
        return ServuxProtocolConfig.litematicsEnabled && registeredPlayers.contains(player.getUUID());
    }

    public static void onBlockEntityRequest(ServerPlayer player, BlockPos pos) {
        if (!hasPermission(player)) {
            return;
        }
        ServerLevel world = player.level();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                world,
                pos.getX() >> 4,
                pos.getZ() >> 4,
                () -> {
                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, pos)) return;
                    BlockEntity be = world.getBlockEntity(pos);
                    CompoundTag tag = be != null ? be.saveWithFullMetadata(MinecraftServer.getServer().registryAccess()) : new CompoundTag();
                    ServuxLitematicaPayload payload = new ServuxLitematicaPayload(ServuxLitematicaPayloadType.PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
                    payload.pos = pos;
                    payload.nbt = tag;
                    encodeServerData(player, payload);
                }
        );
    }

    public static void onEntityRequest(ServerPlayer player, int entityId) {
        if (!hasPermission(player)) {
            return;
        }
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                player.level(),
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(player.position()),
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(player.position()),
                () -> {
                    Entity entity = player.level().getEntity(entityId);
                    if (entity == null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) {
                        return;
                    }
                    CompoundTag tag = new CompoundTag();
                    ServuxLitematicaPayload payload = new ServuxLitematicaPayload(ServuxLitematicaPayloadType.PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE);
                    payload.entityId = entityId;
                    if (entity instanceof net.minecraft.world.entity.player.Player) {
                        Identifier loc = EntityType.getKey(entity.getType());
                        tag = TagUtil.saveEntity(entity);
                        tag.putString("id", loc.toString());
                        payload.nbt = tag;
                        encodeServerData(player, payload);
                    } else if (TagUtil.saveEntityAsPassenger(entity, tag)) {
                        payload.nbt = tag;
                        encodeServerData(player, payload);
                    }
                }
        );
    }

    public static void onBulkEntityRequest(ServerPlayer player, ChunkPos chunkPos, CompoundTag req) {
        if (!isRegistered(player) || req == null || req.isEmpty()) {
            return;
        }

        ServerLevel world = player.level();
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                world,
                chunkPos.x(),
                chunkPos.z(),
                () -> sendBulkEntityData(player, world, chunkPos, req)
        );
    }

    private static void sendBulkEntityData(ServerPlayer player, ServerLevel world, ChunkPos chunkPos, CompoundTag req) {
        ChunkAccess chunk = world.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);

        if (chunk == null) {
            return;
        }
        if (!req.contains("Task") || req.getStringOr("Task", "").equals("BulkEntityRequest")) {
            ServuxProtocol.LOGGER.debug("litematic_data: Sending Bulk NBT Data for ChunkPos [{}] to player {}", chunkPos, player.getName().getString());

            long timeStart = System.currentTimeMillis();
            ListTag tileList = new ListTag();
            ListTag entityList = new ListTag();
            int minY = Math.max(req.getIntOr("minY", world.getMinY()), world.getMinY());
            int maxY = Math.min(req.getIntOr("maxY", world.getMaxY() - 1), world.getMaxY() - 1);
            if (maxY < minY) return;
            BlockPos pos1 = new BlockPos(chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ());
            BlockPos pos2 = new BlockPos(chunkPos.getMaxBlockX(), maxY, chunkPos.getMaxBlockZ());
            AABB bb = AABB.encapsulatingFullBlocks(pos1, pos2);
            Set<BlockPos> teSet = chunk.getBlockEntitiesPos();
            List<Entity> entities = world.getEntitiesOfClass(Entity.class, bb,
                    entity -> !(entity instanceof Player) && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity));
            for (BlockPos tePos : teSet) {
                if ((tePos.getX() < chunkPos.getMinBlockX() || tePos.getX() > chunkPos.getMaxBlockX()) ||
                        (tePos.getZ() < chunkPos.getMinBlockZ() || tePos.getZ() > chunkPos.getMaxBlockZ()) ||
                        (tePos.getY() < minY || tePos.getY() > maxY)) {
                    continue;
                }

                BlockEntity be = world.getBlockEntity(tePos);
                CompoundTag beTag = TagUtil.saveTileWithId(be);
                tileList.add(beTag);
            }

            for (Entity entity : entities) {
                CompoundTag entTag = new CompoundTag();

                if (TagUtil.saveEntity(entity, entTag)) {
                    Vec3 posVec = new Vec3(entity.getX() - pos1.getX(), entity.getY() - pos1.getY(), entity.getZ() - pos1.getZ());
                    NbtUtils.writeEntityPositionToTag(posVec, entTag);
                    entTag.putInt("entityId", entity.getId());
                    entityList.add(entTag);
                }
            }

            CompoundTag output = new CompoundTag();
            output.putString("Task", "BulkEntityReply");
            output.put("TileEntities", tileList);
            output.put("Entities", entityList);
            output.putInt("chunkX", chunkPos.x());
            output.putInt("chunkZ", chunkPos.z());
            ServuxProtocol.LOGGER.debug("process bulk entity used: {}ms", System.currentTimeMillis() - timeStart);

            ServuxLitematicaPayload send = new ServuxLitematicaPayload(ServuxLitematicaPayloadType.PACKET_S2C_NBT_RESPONSE_START);
            send.nbt.merge(output);
            encodeServerData(player, send);
        }
    }

    public static void handleClientPasteRequest(ServerPlayer player, @NotNull CompoundTag tags) {
        if (!hasPermission(player)) {
            player.getBukkitEntity().sendActionBar(Component.text("Insufficient Permissions for the Litematic paste operation", NamedTextColor.RED));
            return;
        }
        if (!player.isCreative()) {
            player.getBukkitEntity().sendActionBar(Component.text("Creative Mode is required for the Litematic paste operation", NamedTextColor.RED));
            return;
        }

        if (tags.getStringOr("Task", "").equals("LitematicaPaste")) {
            try {
                ServuxProtocol.LOGGER.debug("litematic_data: Servux Paste request from player {}", player.getName().getString());
                ServerLevel serverLevel = player.level();
                long timeStart = System.currentTimeMillis();
                SchematicPlacement placement = SchematicPlacement.createFromNbt(tags);
                ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
                placement.pasteTo(serverLevel, replaceMode, player, timeStart);
            } catch (RuntimeException exception) {
                player.getBukkitEntity().sendActionBar(Component.text("Invalid Litematica paste data", NamedTextColor.RED));
                ServuxProtocol.LOGGER.warn("Rejected invalid Litematica paste request from {}", player.getScoreboardName(), exception);
            }
        }
    }

    @Override
    public boolean isActive() {
        return ServuxProtocolConfig.litematicsEnabled;
    }

    public enum ServuxLitematicaPayloadType {
        PACKET_S2C_METADATA(1),
        PACKET_C2S_METADATA_REQUEST(2),
        PACKET_C2S_BLOCK_ENTITY_REQUEST(3),
        PACKET_C2S_ENTITY_REQUEST(4),
        PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE(5),
        PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE(6),
        PACKET_C2S_BULK_ENTITY_NBT_REQUEST(7),
        PACKET_C2S_UNREGISTER_REPLY(8),
        // For Packet Splitter (Oversize Packets, S2C)
        PACKET_S2C_NBT_RESPONSE_START(10),
        PACKET_S2C_NBT_RESPONSE_DATA(11),
        // For Packet Splitter (Oversize Packets, C2S)
        PACKET_C2S_NBT_RESPONSE_START(12),
        PACKET_C2S_NBT_RESPONSE_DATA(13),
        PACKET_C2S_TASK_REQUEST(14),
        PACKET_S2C_TASK_RESPONSE(15),
        PACKET_S2C_TASK_STATUS_SYNC(16),
        PACKET_C2S_TASK_CANCEL(17);

        public final int type;

        ServuxLitematicaPayloadType(int type) {
            this.type = type;
            Helper.ID_TO_TYPE.put(type, this);
        }

        public static ServuxLitematicaPayloadType fromId(int id) {
            return Helper.ID_TO_TYPE.get(id);
        }

        private static final class Helper {
            static Map<Integer, ServuxLitematicaPayloadType> ID_TO_TYPE = new HashMap<>();
        }
    }

    public static class ServuxLitematicaPayload implements LeavesCustomPayload {

        @ID
        public static final Identifier CHANNEL = ServuxProtocol.id("litematics");

        @Codec
        public static final StreamCodec<FriendlyByteBuf, ServuxLitematicaPayload> CODEC = StreamCodec.of(
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
                        case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> {
                            buf.writeChunkPos(payload.chunkPos);
                            ServuxDataByteBuf.write(buf, payload.nbt);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY -> ServuxDataByteBuf.write(buf, payload.nbt);
                        case PACKET_C2S_TASK_REQUEST, PACKET_S2C_TASK_RESPONSE,
                             PACKET_S2C_TASK_STATUS_SYNC, PACKET_C2S_TASK_CANCEL ->
                                ServuxDataByteBuf.write(buf, payload.nbt);
                        case PACKET_S2C_NBT_RESPONSE_DATA, PACKET_C2S_NBT_RESPONSE_DATA ->
                                buf.writeBytes(payload.buffer.readBytes(payload.buffer.readableBytes()));
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> buf.writeNbt(payload.nbt);
                        default -> ServuxProtocol.LOGGER.error("ServuxLitematicaPacket#toPacket: Unknown packet type!");
                    }
                },
                buf -> {
                    ServuxLitematicaPayloadType type = ServuxLitematicaPayloadType.fromId(buf.readVarInt());
                    if (type == null) {
                        throw new IllegalStateException("invalid packet type received");
                    }
                    ServuxLitematicaPayload payload = new ServuxLitematicaPayload(type);
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
                        case PACKET_C2S_BULK_ENTITY_NBT_REQUEST -> {
                            payload.chunkPos = buf.readChunkPos();
                            payload.nbt = ServuxDataByteBuf.read(buf, ServuxProtocolConfig.litematicsMaxNbtSize);
                        }
                        case PACKET_C2S_UNREGISTER_REPLY -> ServuxDataByteBuf.skip(buf);
                        case PACKET_C2S_TASK_REQUEST ->
                                payload.nbt = ServuxDataByteBuf.read(buf, ServuxProtocolConfig.litematicsMaxNbtSize);
                        case PACKET_S2C_TASK_RESPONSE, PACKET_S2C_TASK_STATUS_SYNC, PACKET_C2S_TASK_CANCEL ->
                                payload.nbt = ServuxDataByteBuf.read(buf, 16L * 1024 * 1024);
                        case PACKET_C2S_NBT_RESPONSE_DATA, PACKET_S2C_NBT_RESPONSE_DATA ->
                                payload.buffer = new FriendlyByteBuf(buf.readBytes(buf.readableBytes()));
                        case PACKET_C2S_METADATA_REQUEST, PACKET_S2C_METADATA -> payload.nbt = buf.readNbt();
                    }
                    return payload;
                }
        );

        public static final int PROTOCOL_VERSION = 2;
        private final ServuxLitematicaPayloadType packetType;
        private final int transactionId;
        private int entityId;
        private BlockPos pos;
        private CompoundTag nbt;
        private ChunkPos chunkPos;
        private FriendlyByteBuf buffer;

        private ServuxLitematicaPayload(ServuxLitematicaPayloadType type) {
            this.packetType = type;
            this.transactionId = -1;
            this.entityId = -1;
            this.pos = BlockPos.ZERO;
            this.chunkPos = ChunkPos.ZERO;
            this.nbt = new CompoundTag();
        }

        public int getVersion() {
            return PROTOCOL_VERSION;
        }

        public int getTransactionId() {
            return this.transactionId;
        }

        public int getEntityId() {
            return this.entityId;
        }

        public BlockPos getPos() {
            return this.pos;
        }

        public CompoundTag getCompound() {
            return this.nbt;
        }

        public ChunkPos getChunkPos() {
            return this.chunkPos;
        }

        public FriendlyByteBuf getBuffer() {
            return this.buffer;
        }

        public boolean hasBuffer() {
            return this.buffer != null && this.buffer.isReadable();
        }

        public boolean hasNbt() {
            return this.nbt != null && !this.nbt.isEmpty();
        }

        public boolean isEmpty() {
            return !this.hasBuffer() && !this.hasNbt();
        }
    }
}
