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
import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


// Powered by Servux(https://github.com/sakura-ryoko/servux)

@LeavesProtocol.Register(namespace = "servux")
public class ServuxStructuresProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final int PROTOCOL_VERSION = 3;
    private static final int updateInterval = 40;
    private static final Map<Integer, ServerPlayer> players = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<ChunkPos, Timeout>> timeouts = new ConcurrentHashMap<>();
    private static final Map<UUID, Identifier> playerDimensions = new ConcurrentHashMap<>();
    private static int retainDistance;

    @ProtocolHandler.PlayerJoin
    public static void onPlayerJoin(ServerPlayer player) {
        sendMetaData(player);
    }

    @ProtocolHandler.PayloadReceiver(payload = StructuresPayload.class)
    public static void onPacketReceive(ServerPlayer player, StructuresPayload payload) {
        switch (payload.packetType()) {
            case PACKET_C2S_STRUCTURES_REGISTER -> {
                if (payload.nbt().getIntOr("version", -1) >= PROTOCOL_VERSION && ServuxProtocolConfig.structureProtocol) {
                    onPlayerSubscribed(player);
                }
            }
            case PACKET_C2S_REQUEST_SPAWN_METADATA -> ServuxHudDataProtocol.refreshSpawnMetadata(player); // move to
            case PACKET_C2S_STRUCTURES_UNREGISTER -> onPlayerLoggedOut(player);
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLoggedOut(@NotNull ServerPlayer player) {
        players.remove(player.getId());
        timeouts.remove(player.getUUID());
        playerDimensions.remove(player.getUUID());
    }

    @ProtocolHandler.Ticker
    public static void tick() {
        MinecraftServer server = MinecraftServer.getServer();
        long tickCounter = System.currentTimeMillis() / 50;
        retainDistance = server.getPlayerList().getViewDistance() + 2;
        for (ServerPlayer player : players.values()) {
            Identifier dimension = player.level().dimension().identifier();
            Identifier previousDimension = playerDimensions.put(player.getUUID(), dimension);
            if (previousDimension != null && !previousDimension.equals(dimension)) {
                initialSyncStructures(player, player.moonrise$getViewDistanceHolder().getViewDistances().sendViewDistance() + 2, tickCounter);
            } else {
                refreshTrackedChunks(player, tickCounter);
            }
        }
    }

    public static void onStartedWatchingChunk(ServerPlayer player, LevelChunk chunk) {
        if (players.containsKey(player.getId())) {
            addChunkTimeoutIfHasReferences(player.getUUID(), chunk, System.currentTimeMillis() / 50);
        }
    }

    private static void addChunkTimeoutIfHasReferences(final UUID uuid, LevelChunk chunk, final long tickCounter) {
        final ChunkPos pos = chunk.getPos();

        if (chunkHasStructureReferences(pos.x(), pos.z(), chunk.getLevel())) { // Leaves - Paper 26.1: ChunkPos record accessors
            final Map<ChunkPos, Timeout> map = timeouts.computeIfAbsent(uuid, (u) -> new ConcurrentHashMap<>());
            map.computeIfAbsent(pos, (p) -> new Timeout(tickCounter - ServuxProtocolConfig.structureTimeout));
        }
    }

    private static boolean chunkHasStructureReferences(int chunkX, int chunkZ, @NotNull Level world) {
        if (!world.hasChunk(chunkX, chunkZ)) {
            return false;
        }

        ChunkAccess chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS, false);

        if (chunk != null) {
            for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void onPlayerSubscribed(@NotNull ServerPlayer player) {
        if (!players.containsKey(player.getId())) {
            players.put(player.getId(), player);
            playerDimensions.put(player.getUUID(), player.level().dimension().identifier());
        } else {
            LOGGER.warn("{} re-register servux:structures", player.getScoreboardName());
        }

        MinecraftServer server = MinecraftServer.getServer();
        sendMetaData(player);
        initialSyncStructures(player, player.moonrise$getViewDistanceHolder().getViewDistances().sendViewDistance() + 2, System.currentTimeMillis() / 50);
    }

    private static void sendMetaData(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", "structure_bounding_boxes");
        tag.putString("id", StructuresPayload.CHANNEL.toString());
        tag.putInt("version", PROTOCOL_VERSION);
        tag.putString("servux", ServuxProtocol.SERVUX_STRING);
        tag.putInt("timeout", ServuxProtocolConfig.structureTimeout);

        sendPacket(player, new StructuresPayload(StructuresPayloadType.PACKET_S2C_METADATA, tag));
    }

    public static void initialSyncStructures(ServerPlayer player, int chunkRadius, long tickCounter) {
        UUID uuid = player.getUUID();
        ChunkPos center = player.getLastSectionPos().chunk();
        Map<Structure, LongSet> references = getStructureReferences(player.level(), center, chunkRadius);

        timeouts.remove(uuid);

        sendStructures(player, references, tickCounter);
    }

    public static Map<Structure, LongSet> getStructureReferences(ServerLevel world, ChunkPos center, int chunkRadius) {
        Map<Structure, LongSet> references = new HashMap<>();

        for (int cx = center.x() - chunkRadius; cx <= center.x() + chunkRadius; ++cx) { // Leaves - Paper 26.1: ChunkPos record accessors
            for (int cz = center.z() - chunkRadius; cz <= center.z() + chunkRadius; ++cz) { // Leaves - Paper 26.1: ChunkPos record accessors
                getReferencesFromChunk(cx, cz, world, references);
            }
        }

        return references;
    }

    public static void getReferencesFromChunk(int chunkX, int chunkZ, Level world, Map<Structure, LongSet> references) {
        if (!world.hasChunk(chunkX, chunkZ)) {
            return;
        }

        ChunkAccess chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS, false);

        if (chunk != null) {
            for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                Structure feature = entry.getKey();
                LongSet startChunks = entry.getValue();

                // TODO add an option && feature != StructureFeature.MINESHAFT (?)
                if (!startChunks.isEmpty()) {
                    references.merge(feature, startChunks, (oldSet, entrySet) -> {
                        LongOpenHashSet newSet = new LongOpenHashSet(oldSet);
                        newSet.addAll(entrySet);
                        return newSet;
                    });
                }
            }
        }
    }

    public static void sendStructures(ServerPlayer player, Map<Structure, LongSet> references, long tickCounter) {
        ServerLevel world = player.level();
        Map<ChunkPos, StructureStart> starts = getStructureStarts(world, references);

        if (!starts.isEmpty()) {
            addOrRefreshTimeouts(player.getUUID(), references, tickCounter);

            ListTag structureList = getStructureList(starts, world);

            if (players.containsKey(player.getId())) {
                CompoundTag test = new CompoundTag();
                test.put("Structures", structureList.copy());
                sendPacket(player, new StructuresPayload(StructuresPayloadType.PACKET_S2C_STRUCTURE_DATA_START, test));
            }
        }
    }

    public static ListTag getStructureList(Map<ChunkPos, StructureStart> structures, ServerLevel world) {
        ListTag list = new ListTag();
        StructurePieceSerializationContext ctx = StructurePieceSerializationContext.fromLevel(world);

        for (Map.Entry<ChunkPos, StructureStart> entry : structures.entrySet()) {
            ChunkPos pos = entry.getKey();
            list.add(entry.getValue().createTag(ctx, pos));
        }

        return list;
    }

    public static Map<ChunkPos, StructureStart> getStructureStarts(ServerLevel world, Map<Structure, LongSet> references) {
        Map<ChunkPos, StructureStart> starts = new HashMap<>();

        for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
            Structure structure = entry.getKey();
            LongSet startChunks = entry.getValue();
            LongIterator iter = startChunks.iterator();

            while (iter.hasNext()) {
                ChunkPos pos = ChunkPos.unpack(iter.nextLong()); // Leaves - Paper 26.1: ChunkPos constructor replaced with unpack

                if (!world.hasChunk(pos.x(), pos.z())) { // Leaves - Paper 26.1: ChunkPos record accessors
                    continue;
                }

                ChunkAccess chunk = world.getChunk(pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS, false); // Leaves - Paper 26.1: ChunkPos record accessors
                StructureStart start = null;
                if (chunk != null) {
                    start = chunk.getStartForStructure(structure);
                }

                if (start != null) {
                    starts.put(pos, start);
                }
            }
        }

        return starts;
    }

    public static void refreshTrackedChunks(ServerPlayer player, long tickCounter) {
        UUID uuid = player.getUUID();
        Map<ChunkPos, Timeout> map = timeouts.get(uuid);

        if (map != null) {
            sendAndRefreshExpiredStructures(player, map, tickCounter);
        }
    }

    public static void sendAndRefreshExpiredStructures(ServerPlayer player, Map<ChunkPos, Timeout> map, long tickCounter) {
        Set<ChunkPos> positionsToUpdate = new HashSet<>();

        for (Map.Entry<ChunkPos, Timeout> entry : map.entrySet()) {
            Timeout out = entry.getValue();

            if (out.needsUpdate(ServuxProtocolConfig.structureTimeout, tickCounter)) {
                positionsToUpdate.add(entry.getKey());
            }
        }

        if (!positionsToUpdate.isEmpty()) {
            ServerLevel world = player.level();
            ChunkPos center = player.getLastSectionPos().chunk();
            Map<Structure, LongSet> references = new HashMap<>();

            for (ChunkPos pos : positionsToUpdate) {
                if (isOutOfRange(pos, center)) {
                    map.remove(pos);
                } else {
                    getReferencesFromChunk(pos.x(), pos.z(), world, references); // Leaves - Paper 26.1: ChunkPos record accessors

                    Timeout timeout = map.get(pos);

                    if (timeout != null) {
                        timeout.setLastSync(tickCounter);
                    }
                }
            }

            if (!references.isEmpty()) {
                sendStructures(player, references, tickCounter);
            }
        }
    }

    protected static boolean isOutOfRange(ChunkPos pos, ChunkPos center) {
        return Math.abs(pos.x() - center.x()) > retainDistance || Math.abs(pos.z() - center.z()) > retainDistance; // Leaves - Paper 26.1: ChunkPos record accessors
    }

    public static void addOrRefreshTimeouts(final UUID uuid, final Map<Structure, LongSet> references, final long tickCounter) {
        Map<ChunkPos, Timeout> map = timeouts.computeIfAbsent(uuid, (u) -> new ConcurrentHashMap<>());

        for (LongSet chunks : references.values()) {
            for (Long chunkPosLong : chunks) {
                final ChunkPos pos = ChunkPos.unpack(chunkPosLong); // Leaves - Paper 26.1: ChunkPos constructor replaced with unpack
                map.computeIfAbsent(pos, (p) -> new Timeout(tickCounter)).setLastSync(tickCounter);
            }
        }
    }

    public static void sendPacket(ServerPlayer player, StructuresPayload payload) {
        if (payload.packetType() == StructuresPayloadType.PACKET_S2C_STRUCTURE_DATA_START) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            ServuxDataByteBuf.write(buffer, payload.nbt());
            PacketSplitter.send(ServuxStructuresProtocol::sendWithSplitter, buffer, player);
        } else {
            ProtocolUtils.sendPayloadPacket(player, payload);
        }
    }

    private static void sendWithSplitter(ServerPlayer player, FriendlyByteBuf buf) {
        sendPacket(player, new StructuresPayload(StructuresPayloadType.PACKET_S2C_STRUCTURE_DATA, buf));
    }

    @Override
    public int tickerInterval(String tickerID) {
        return updateInterval;
    }

    @Override
    public boolean isActive() {
        return ServuxProtocolConfig.structureProtocol;
    }

    public enum StructuresPayloadType {
        PACKET_S2C_METADATA(1),
        PACKET_S2C_STRUCTURE_DATA(2),
        PACKET_C2S_STRUCTURES_REGISTER(3),
        PACKET_C2S_STRUCTURES_UNREGISTER(4),
        PACKET_S2C_STRUCTURE_DATA_START(5),
        PACKET_S2C_SPAWN_METADATA(10),
        PACKET_C2S_REQUEST_SPAWN_METADATA(11),
        PACKET_S2C_WEATHER_DATA(12);

        public final int type;

        StructuresPayloadType(int type) {
            this.type = type;
            Helper.ID_TO_TYPE.put(type, this);
        }

        public static StructuresPayloadType fromId(int id) {
            return Helper.ID_TO_TYPE.get(id);
        }

        private static final class Helper {
            static Map<Integer, StructuresPayloadType> ID_TO_TYPE = new HashMap<>();
        }
    }

    public record StructuresPayload(StructuresPayloadType packetType, CompoundTag nbt,
                                    FriendlyByteBuf buffer) implements LeavesCustomPayload {

        @ID
        public static final Identifier CHANNEL = ServuxProtocol.id("structures");

        @Codec
        public static final StreamCodec<FriendlyByteBuf, StructuresPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.packetType().type);
                    if (payload.packetType().equals(StructuresPayloadType.PACKET_S2C_STRUCTURE_DATA)) {
                        buf.writeBytes(payload.buffer().readBytes(payload.buffer().readableBytes()));
                    } else {
                        buf.writeNbt(payload.nbt());
                    }
                },
                buf -> {
                    int i = buf.readVarInt();
                    StructuresPayloadType type = StructuresPayloadType.fromId(i);
                    if (type == null) {
                        throw new IllegalStateException("invalid packet type received");
                    } else if (type.equals(StructuresPayloadType.PACKET_S2C_STRUCTURE_DATA)) {
                        return new StructuresPayload(type, new FriendlyByteBuf(buf.readBytes(buf.readableBytes())));
                    } else {
                        return new StructuresPayload(type, buf.readNbt());
                    }
                }
        );

        public StructuresPayload(StructuresPayloadType packetType, CompoundTag nbt) {
            this(packetType, nbt, null);
        }

        public StructuresPayload(StructuresPayloadType packetType, FriendlyByteBuf buffer) {
            this(packetType, new CompoundTag(), buffer);
        }
    }

    public static class Timeout {
        private long lastSync;

        public Timeout(long currentTick) {
            this.lastSync = currentTick;
        }

        public boolean needsUpdate(int timeout, long currentTick) {
            return timeout == -1 || currentTick - this.lastSync >= timeout;
        }

        public void setLastSync(long tickCounter) {
            this.lastSync = tickCounter;
        }
    }
}
