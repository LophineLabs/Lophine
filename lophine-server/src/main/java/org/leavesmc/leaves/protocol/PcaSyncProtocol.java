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

package org.leavesmc.leaves.protocol;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.config.modules.function.protocol.PcaSyncProtocolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.util.TagUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@LeavesProtocol.Register(namespace = "pca")
public class PcaSyncProtocol implements LeavesProtocol {
    public static final String PROTOCOL_ID = "pca";

    private static final Identifier ENABLE_PCA_SYNC_PROTOCOL = id("enable_pca_sync_protocol");
    private static final Identifier DISABLE_PCA_SYNC_PROTOCOL = id("disable_pca_sync_protocol");

    private static final ConcurrentMap<UUID, Pair<Identifier, BlockPos>> playerWatchBlockPos = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Pair<Identifier, UUID>> playerWatchEntity = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Pair<Identifier, BlockPos>, Set<UUID>> blockPosWatchPlayerSet = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Pair<Identifier, UUID>, Set<UUID>> entityWatchPlayerSet = new ConcurrentHashMap<>();

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID, path);
    }

    @ProtocolHandler.PlayerJoin
    private static void onJoin(ServerPlayer player) {
        if (PcaSyncProtocolConfig.enabled) {
            enablePcaSyncProtocol(player);
        }
    }

    @ProtocolHandler.PlayerLeave
    private static void onLeave(ServerPlayer player) {
        clearPlayerWatchData(player);
    }

    @ProtocolHandler.BytebufReceiver(key = "cancel_sync_block_entity")
    private static void cancelSyncBlockEntityHandler(ServerPlayer player, FriendlyByteBuf buf) {
        clearPlayerWatchBlock(player);
    }

    @ProtocolHandler.BytebufReceiver(key = "cancel_sync_entity")
    private static void cancelSyncEntityHandler(ServerPlayer player, FriendlyByteBuf buf) {
        clearPlayerWatchEntity(player);
    }

    @ProtocolHandler.PayloadReceiver(payload = SyncBlockEntityPayload.class)
    private static void syncBlockEntityHandler(ServerPlayer player, SyncBlockEntityPayload payload) {
        if (!PcaSyncProtocolConfig.enabled) {
            return;
        }

        UUID playerId = player.getUUID();
        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (!PcaSyncProtocolConfig.enabled || onlinePlayer == null || onlinePlayer.hasDisconnected() || !TickThread.isTickThreadFor(onlinePlayer)) {
                return;
            }

            ServerLevel world = onlinePlayer.level();
            BlockPos pos = payload.pos;
            Bukkit.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(), pos.getX() >> 4, pos.getZ() >> 4, () -> {
                if (!PcaSyncProtocolConfig.enabled || MinecraftServer.getServer().getPlayerList().getPlayer(playerId) == null || !TickThread.isTickThreadFor(world, pos)) {
                    return;
                }

                BlockState blockState = world.getBlockState(pos);
                clearPlayerWatchData(playerId);

                BlockEntity blockEntityAdj = null;
                if (blockState.getBlock() instanceof ChestBlock && blockState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos posAdj = pos.relative(ChestBlock.getConnectedDirection(blockState));
                    if (TickThread.isTickThreadFor(world, posAdj)) {
                        blockEntityAdj = getLoadedBlockEntity(world, posAdj);
                    } else {
                        scheduleBlockEntityUpdate(playerId, world, posAdj);
                    }
                }

                if (blockEntityAdj != null) {
                    updateBlockEntity(playerId, blockEntityAdj);
                }

                BlockEntity blockEntity = getLoadedBlockEntity(world, pos);
                if (blockEntity != null) {
                    updateBlockEntity(playerId, blockEntity);
                }

                Pair<Identifier, BlockPos> pair = new ImmutablePair<>(world.dimension().identifier(), pos);
                playerWatchBlockPos.put(playerId, pair);
                blockPosWatchPlayerSet.computeIfAbsent(pair, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
            });
        }, null, 1L);
    }

    @ProtocolHandler.PayloadReceiver(payload = SyncEntityPayload.class)
    private static void syncEntityHandler(ServerPlayer player, SyncEntityPayload payload) {
        if (!PcaSyncProtocolConfig.enabled) {
            return;
        }

        UUID playerId = player.getUUID();
        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (!PcaSyncProtocolConfig.enabled || onlinePlayer == null || onlinePlayer.hasDisconnected() || !TickThread.isTickThreadFor(onlinePlayer)) {
                return;
            }

            ServerLevel world = onlinePlayer.level();
            Entity entity = world.getEntity(payload.entityId);
            if (entity == null) {
                return;
            }

            boolean viewerIsOp = MinecraftServer.getServer().getPlayerList().isOp(onlinePlayer.nameAndId());
            if (TickThread.isTickThreadFor(entity)) {
                syncEntityForWatcher(playerId, viewerIsOp, entity);
            } else {
                entity.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> syncEntityForWatcher(playerId, viewerIsOp, entity), null, 1L);
            }
        }, null, 1L);
    }

    public static void onConfigModify(boolean enable) {
        if (enable) {
            enablePcaSyncProtocolGlobal();
        } else {
            disablePcaSyncProtocolGlobal();
        }
    }

    public static void enablePcaSyncProtocol(@NotNull ServerPlayer player) {
        sendEmptyPacket(player.getUUID(), ENABLE_PCA_SYNC_PROTOCOL);
    }

    public static void disablePcaSyncProtocol(@NotNull ServerPlayer player) {
        sendEmptyPacket(player.getUUID(), DISABLE_PCA_SYNC_PROTOCOL);
    }

    public static void updateEntity(@NotNull ServerPlayer player, @NotNull Entity entity) {
        updateEntity(player.getUUID(), entity);
    }

    private static void updateEntity(@NotNull UUID playerId, @NotNull Entity entity) {
        if (!TickThread.isTickThreadFor(entity) || entity.isRemoved()) {
            return;
        }
        CompoundTag nbt = TagUtil.saveEntity(entity);
        sendPayloadPacket(playerId, new UpdateEntityPayload(entity.level().dimension().identifier(), entity.getId(), nbt));
    }

    public static void updateBlockEntity(@NotNull ServerPlayer player, @NotNull BlockEntity blockEntity) {
        updateBlockEntity(player.getUUID(), blockEntity);
    }

    private static void updateBlockEntity(@NotNull UUID playerId, @NotNull BlockEntity blockEntity) {
        Level world = blockEntity.getLevel();
        if (world == null || world.isClientSide() || !TickThread.isTickThreadFor(world, blockEntity.getBlockPos())) {
            return;
        }

        sendPayloadPacket(playerId, new UpdateBlockEntityPayload(world.dimension().identifier(), blockEntity.getBlockPos(), blockEntity.saveWithoutMetadata(world.registryAccess())));
    }

    public static boolean syncEntityToClient(@NotNull Entity entity) {
        if (!PcaSyncProtocolConfig.enabled || entity.level().isClientSide() || !TickThread.isTickThreadFor(entity) || entity.isRemoved()) {
            return false;
        }

        Pair<Identifier, UUID> pair = new ImmutablePair<>(entity.level().dimension().identifier(), entity.getUUID());
        Set<UUID> playerIds = entityWatchPlayerSet.get(pair);
        if (playerIds == null || playerIds.isEmpty()) {
            return false;
        }

        ArrayList<UUID> stalePlayers = new ArrayList<>();
        boolean ret = false;
        for (UUID playerId : playerIds) {
            if (MinecraftServer.getServer().getPlayerList().getPlayer(playerId) == null) {
                stalePlayers.add(playerId);
                continue;
            }
            updateEntity(playerId, entity);
            ret = true;
        }
        stalePlayers.forEach(playerIds::remove);
        if (playerIds.isEmpty()) {
            entityWatchPlayerSet.remove(pair, playerIds);
        }
        return ret;
    }

    public static boolean syncBlockEntityToClient(@NotNull BlockEntity blockEntity) {
        Level world = blockEntity.getLevel();
        if (!PcaSyncProtocolConfig.enabled || world == null || world.isClientSide() || !TickThread.isTickThreadFor(world, blockEntity.getBlockPos())) {
            return false;
        }

        BlockPos pos = blockEntity.getBlockPos();
        BlockState blockState = world.getBlockState(pos);
        Set<UUID> playerIds = collectWatchers(world, pos);

        if (blockState.getBlock() instanceof ChestBlock && blockState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos posAdj = pos.relative(ChestBlock.getConnectedDirection(blockState));
            playerIds.addAll(collectWatchers(world, posAdj));
        }

        if (playerIds.isEmpty()) {
            return false;
        }

        boolean ret = false;
        for (UUID playerId : playerIds) {
            if (MinecraftServer.getServer().getPlayerList().getPlayer(playerId) == null) {
                removePlayerFromWatchers(playerId);
                continue;
            }
            updateBlockEntity(playerId, blockEntity);
            ret = true;
        }
        return ret;
    }

    public static void disablePcaSyncProtocolGlobal() {
        playerWatchBlockPos.clear();
        playerWatchEntity.clear();
        blockPosWatchPlayerSet.clear();
        entityWatchPlayerSet.clear();
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            disablePcaSyncProtocol(player);
        }
    }

    public static void enablePcaSyncProtocolGlobal() {
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            enablePcaSyncProtocol(player);
        }
    }

    public static void clearPlayerWatchData(ServerPlayer player) {
        clearPlayerWatchData(player.getUUID());
    }

    private static void clearPlayerWatchData(UUID playerId) {
        clearPlayerWatchBlock(playerId);
        clearPlayerWatchEntity(playerId);
    }

    private static void clearPlayerWatchEntity(ServerPlayer player) {
        clearPlayerWatchEntity(player.getUUID());
    }

    private static void clearPlayerWatchEntity(UUID playerId) {
        Pair<Identifier, UUID> pair = playerWatchEntity.remove(playerId);
        if (pair != null) {
            removeFromSet(entityWatchPlayerSet, pair, playerId);
        }
    }

    private static void clearPlayerWatchBlock(ServerPlayer player) {
        clearPlayerWatchBlock(player.getUUID());
    }

    private static void clearPlayerWatchBlock(UUID playerId) {
        Pair<Identifier, BlockPos> pair = playerWatchBlockPos.remove(playerId);
        if (pair != null) {
            removeFromSet(blockPosWatchPlayerSet, pair, playerId);
        }
    }

    private static void removePlayerFromWatchers(UUID playerId) {
        Pair<Identifier, BlockPos> blockPair = playerWatchBlockPos.remove(playerId);
        if (blockPair != null) {
            removeFromSet(blockPosWatchPlayerSet, blockPair, playerId);
        }

        Pair<Identifier, UUID> entityPair = playerWatchEntity.remove(playerId);
        if (entityPair != null) {
            removeFromSet(entityWatchPlayerSet, entityPair, playerId);
        }
    }

    private static <K> void removeFromSet(ConcurrentMap<K, Set<UUID>> map, K key, UUID playerId) {
        Set<UUID> playerSet = map.get(key);
        if (playerSet != null) {
            playerSet.remove(playerId);
            if (playerSet.isEmpty()) {
                map.remove(key, playerSet);
            }
        }
    }

    private static Set<UUID> collectWatchers(@NotNull Level world, @NotNull BlockPos blockPos) {
        Set<UUID> playerIds = blockPosWatchPlayerSet.get(new ImmutablePair<>(world.dimension().identifier(), blockPos));
        return playerIds == null ? new HashSet<>() : new HashSet<>(playerIds);
    }

    private static @org.jetbrains.annotations.Nullable BlockEntity getLoadedBlockEntity(ServerLevel world, BlockPos pos) {
        LevelChunk chunk = world.getChunkIfLoaded(pos);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private static void scheduleBlockEntityUpdate(UUID playerId, ServerLevel world, BlockPos pos) {
        Bukkit.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(), pos.getX() >> 4, pos.getZ() >> 4, () -> {
            if (!PcaSyncProtocolConfig.enabled || MinecraftServer.getServer().getPlayerList().getPlayer(playerId) == null || !TickThread.isTickThreadFor(world, pos)) {
                return;
            }

            BlockEntity blockEntity = getLoadedBlockEntity(world, pos);
            if (blockEntity != null) {
                updateBlockEntity(playerId, blockEntity);
            }
        });
    }

    private static void syncEntityForWatcher(UUID playerId, boolean viewerIsOp, Entity entity) {
        if (!PcaSyncProtocolConfig.enabled || MinecraftServer.getServer().getPlayerList().getPlayer(playerId) == null || !TickThread.isTickThreadFor(entity) || entity.isRemoved()) {
            return;
        }

        if (entity instanceof Player && !maySyncPlayerEntity(playerId, viewerIsOp, entity)) {
            return;
        }

        clearPlayerWatchData(playerId);
        updateEntity(playerId, entity);

        Pair<Identifier, UUID> pair = new ImmutablePair<>(entity.level().dimension().identifier(), entity.getUUID());
        playerWatchEntity.put(playerId, pair);
        entityWatchPlayerSet.computeIfAbsent(pair, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    private static boolean maySyncPlayerEntity(UUID viewerId, boolean viewerIsOp, Entity entity) {
        return switch (PcaSyncProtocolConfig.syncPlayerEntity) {
            case NOBODY -> false;
            case BOT -> entity instanceof ServerBot;
            case OPS -> entity instanceof ServerBot || viewerIsOp;
            case OPS_AND_SELF -> entity instanceof ServerBot || viewerIsOp || entity.getUUID().equals(viewerId);
            case EVERYONE -> true;
        };
    }

    private static void sendEmptyPacket(UUID playerId, Identifier id) {
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            removePlayerFromWatchers(playerId);
            return;
        }

        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (onlinePlayer != null) {
                ProtocolUtils.sendEmptyPacket(onlinePlayer, id);
            }
        }, null, 1L);
    }

    private static void sendPayloadPacket(UUID playerId, CustomPacketPayload payload) {
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            removePlayerFromWatchers(playerId);
            return;
        }

        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (onlinePlayer != null) {
                ProtocolUtils.sendPayloadPacket(onlinePlayer, payload);
            }
        }, null, 1L);
    }

    @Override
    public boolean isActive() {
        return PcaSyncProtocolConfig.enabled;
    }

    public record UpdateEntityPayload(Identifier dimension, int entityId,
                                      CompoundTag tag) implements LeavesCustomPayload {
        @ID
        public static final Identifier UPDATE_ENTITY = PcaSyncProtocol.id("update_entity");

        @Codec
        public static final StreamCodec<FriendlyByteBuf, UpdateEntityPayload> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC,
                UpdateEntityPayload::dimension,
                ByteBufCodecs.INT,
                UpdateEntityPayload::entityId,
                ByteBufCodecs.COMPOUND_TAG,
                UpdateEntityPayload::tag,
                UpdateEntityPayload::new
        );
    }

    public record UpdateBlockEntityPayload(Identifier dimension, BlockPos blockPos,
                                           CompoundTag tag) implements LeavesCustomPayload {
        @ID
        private static final Identifier UPDATE_BLOCK_ENTITY = PcaSyncProtocol.id("update_block_entity");

        @Codec
        private static final StreamCodec<FriendlyByteBuf, UpdateBlockEntityPayload> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC,
                UpdateBlockEntityPayload::dimension,
                BlockPos.STREAM_CODEC,
                UpdateBlockEntityPayload::blockPos,
                ByteBufCodecs.COMPOUND_TAG,
                UpdateBlockEntityPayload::tag,
                UpdateBlockEntityPayload::new
        );
    }

    public record SyncBlockEntityPayload(BlockPos pos) implements LeavesCustomPayload {
        @ID
        public static final Identifier SYNC_BLOCK_ENTITY = PcaSyncProtocol.id("sync_block_entity");

        @Codec
        private static final StreamCodec<FriendlyByteBuf, SyncBlockEntityPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SyncBlockEntityPayload::pos, SyncBlockEntityPayload::new
        );
    }

    public record SyncEntityPayload(int entityId) implements LeavesCustomPayload {
        @ID
        public static final Identifier SYNC_ENTITY = PcaSyncProtocol.id("sync_entity");

        @Codec
        private static final StreamCodec<FriendlyByteBuf, SyncEntityPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, SyncEntityPayload::entityId, SyncEntityPayload::new
        );
    }
}
