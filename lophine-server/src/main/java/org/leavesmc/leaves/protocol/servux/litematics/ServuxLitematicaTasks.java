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

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.leavesmc.leaves.protocol.servux.ServuxProtocol;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, chunk-scoped implementation of Servux's Litematica Fill/Delete tasks.
 */
public final class ServuxLitematicaTasks {
    private static final long MAX_TASK_BLOCKS = 1_000_000L;
    private static final int MAX_TASK_CHUNKS = 4096;
    private static final int MAX_BLOCK_COORDINATE = 30_000_000;
    private static final Map<UUID, AtomicBoolean> ACTIVE_TASKS = new ConcurrentHashMap<>();

    private ServuxLitematicaTasks() {
    }

    public static void handleRequest(ServerPlayer player, CompoundTag request) {
        if (!ServuxLitematicsProtocol.hasPermission(player)
                || !ServuxProtocol.hasPermissionLevel(player, ServuxProtocolConfig.litematicsTaskPermissionLevel)) {
            reply(player, "Servux Litematica tasks require additional permission");
            return;
        }
        if (!player.isCreative()) {
            reply(player, "Creative mode is required for Servux Litematica tasks");
            return;
        }

        String taskName = request.getStringOr("Task", "");
        boolean deleting = taskName.equals("Delete");
        if (!deleting && !taskName.equals("Fill")) {
            reply(player, "Unsupported Servux Litematica task");
            return;
        }

        ServerLevel world = player.level();
        BlockState fillState = deleting ? Blocks.AIR.defaultBlockState() : decodeBlockState(player, request.get("FillState"));
        if (fillState == null) {
            reply(player, "Invalid Servux Litematica fill state");
            return;
        }
        BlockState replaceState = null;
        if (request.contains("ReplaceState")) {
            replaceState = decodeBlockState(player, request.get("ReplaceState"));
            if (replaceState == null) {
                reply(player, "Invalid Servux Litematica replace state");
                return;
            }
        }

        ListTag boxTags = request.getListOrEmpty("Boxes");
        if (boxTags.isEmpty()) {
            reply(player, "Servux Litematica task has no boxes");
            return;
        }

        List<BlockBox> boxes = new ArrayList<>(boxTags.size());
        long totalBlocks = 0;
        try {
            for (Tag tag : boxTags) {
                if (!(tag instanceof CompoundTag compound)) {
                    reply(player, "Invalid Servux Litematica box data");
                    return;
                }
                BlockPos first = decodeBlockPos(compound.get("pos1"));
                BlockPos second = decodeBlockPos(compound.get("pos2"));
                if (first == null || second == null) {
                    reply(player, "Invalid Servux Litematica box coordinates");
                    return;
                }
                BlockBox box = BlockBox.of(first, second);
                if (!isValidBox(world, box)) {
                    reply(player, "Servux Litematica box is outside the world bounds");
                    return;
                }
                long volume = volumeWithinLimit(box);
                if (volume < 0 || totalBlocks > MAX_TASK_BLOCKS - volume) {
                    reply(player, "Servux Litematica task exceeds the block limit");
                    return;
                }
                totalBlocks += volume;
                boxes.add(box);
            }
        } catch (RuntimeException exception) {
            ServuxProtocol.LOGGER.warn("Rejected malformed Servux Litematica task from {}", player.getScoreboardName(), exception);
            reply(player, "Invalid Servux Litematica task data");
            return;
        }

        Map<ChunkPos, List<BlockBox>> chunkBoxes = splitIntoChunks(boxes);
        if (chunkBoxes == null || chunkBoxes.isEmpty() || chunkBoxes.size() > MAX_TASK_CHUNKS) {
            reply(player, "Servux Litematica task touches too many chunks");
            return;
        }
        for (ChunkPos chunk : chunkBoxes.keySet()) {
            if (!world.getChunkSource().hasChunk(chunk.x(), chunk.z())) {
                reply(player, "Servux Litematica tasks only run in already loaded chunks");
                return;
            }
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean previous = ACTIVE_TASKS.put(player.getUUID(), cancelled);
        if (previous != null) previous.set(true);
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger remaining = new AtomicInteger(chunkBoxes.size());
        boolean removeEntities = request.getBooleanOr("RemoveEntities", false);
        String displayName = deleting ? "Delete" : "Fill";
        BlockState finalReplaceState = replaceState;
        for (Map.Entry<ChunkPos, List<BlockBox>> entry : chunkBoxes.entrySet()) {
            ChunkPos chunk = entry.getKey();
            List<BlockBox> regions = List.copyOf(entry.getValue());
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                    world,
                    chunk.x(),
                    chunk.z(),
                    () -> applyChunkTask(player, world, chunk, regions, fillState, finalReplaceState, removeEntities,
                            cancelled, failed, remaining, displayName)
            );
        }
        reply(player, "Servux Litematica " + displayName + " queued for " + chunkBoxes.size() + " chunks");
    }

    public static void cancel(ServerPlayer player) {
        AtomicBoolean task = ACTIVE_TASKS.remove(player.getUUID());
        if (task != null) {
            task.set(true);
            reply(player, "Servux Litematica task cancellation requested");
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        AtomicBoolean task = ACTIVE_TASKS.remove(player.getUUID());
        if (task != null) task.set(true);
    }

    private static BlockState decodeBlockState(ServerPlayer player, Tag tag) {
        if (tag == null) return null;
        try {
            return BlockState.CODEC.parse(
                    player.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                    tag
            ).result().orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static BlockPos decodeBlockPos(Tag tag) {
        if (tag == null) return null;
        return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
    }

    private static boolean isValidBox(ServerLevel world, BlockBox box) {
        BlockPos min = box.min();
        BlockPos max = box.max();
        return min.getY() >= world.getMinY()
                && max.getY() < world.getMaxY()
                && min.getX() >= -MAX_BLOCK_COORDINATE
                && max.getX() <= MAX_BLOCK_COORDINATE
                && min.getZ() >= -MAX_BLOCK_COORDINATE
                && max.getZ() <= MAX_BLOCK_COORDINATE
                && world.getWorldBorder().isWithinBounds(min)
                && world.getWorldBorder().isWithinBounds(max);
    }

    private static long volumeWithinLimit(BlockBox box) {
        long x = (long) box.max().getX() - box.min().getX() + 1;
        long y = (long) box.max().getY() - box.min().getY() + 1;
        long z = (long) box.max().getZ() - box.min().getZ() + 1;
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_TASK_BLOCKS || y > MAX_TASK_BLOCKS || z > MAX_TASK_BLOCKS) {
            return -1;
        }
        long xy = x * y;
        if (xy > MAX_TASK_BLOCKS / z) return -1;
        return xy * z;
    }

    private static Map<ChunkPos, List<BlockBox>> splitIntoChunks(List<BlockBox> boxes) {
        Map<ChunkPos, List<BlockBox>> result = new LinkedHashMap<>();
        for (BlockBox box : boxes) {
            int minChunkX = box.min().getX() >> 4;
            int maxChunkX = box.max().getX() >> 4;
            int minChunkZ = box.min().getZ() >> 4;
            int maxChunkZ = box.max().getZ() >> 4;
            long chunkCount = ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
            if (chunkCount > MAX_TASK_CHUNKS) return null;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    int minX = Math.max(box.min().getX(), chunkX << 4);
                    int maxX = Math.min(box.max().getX(), (chunkX << 4) + 15);
                    int minZ = Math.max(box.min().getZ(), chunkZ << 4);
                    int maxZ = Math.min(box.max().getZ(), (chunkZ << 4) + 15);
                    ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                    result.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(new BlockBox(
                            new BlockPos(minX, box.min().getY(), minZ),
                            new BlockPos(maxX, box.max().getY(), maxZ)));
                    if (result.size() > MAX_TASK_CHUNKS) return null;
                }
            }
        }
        return result;
    }

    private static void applyChunkTask(ServerPlayer player, ServerLevel world, ChunkPos chunk, List<BlockBox> boxes,
                                       BlockState fillState, BlockState replaceState, boolean removeEntities,
                                       AtomicBoolean cancelled, AtomicBoolean failed, AtomicInteger remaining,
                                       String displayName) {
        try {
            if (cancelled.get() || world.getChunk(chunk.x(), chunk.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
                return;
            }
            for (BlockBox box : boxes) {
                if (cancelled.get()) return;
                if (removeEntities) {
                    for (Entity entity : world.getEntitiesOfClass(Entity.class, box.aabb(),
                            candidate -> !(candidate instanceof Player) && TickThread.isTickThreadFor(candidate))) {
                        entity.discard();
                    }
                }

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int z = box.min().getZ(); z <= box.max().getZ() && !cancelled.get(); z++) {
                    for (int x = box.min().getX(); x <= box.max().getX() && !cancelled.get(); x++) {
                        for (int y = box.max().getY(); y >= box.min().getY(); y--) {
                            if (cancelled.get()) return;
                            pos.set(x, y, z);
                            BlockState oldState = world.getBlockState(pos);
                            if (replaceState == null ? oldState.equals(fillState) : !oldState.equals(replaceState)) {
                                continue;
                            }
                            BlockEntity blockEntity = world.getBlockEntity(pos);
                            if (blockEntity instanceof Container container) {
                                container.clearContent();
                                world.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 0x32);
                            }
                            world.setBlock(pos, fillState, 0x32);
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            failed.set(true);
            ServuxProtocol.LOGGER.warn("Servux Litematica {} task failed in chunk {}", displayName, chunk, exception);
        } finally {
            if (remaining.decrementAndGet() == 0) {
                ACTIVE_TASKS.remove(player.getUUID(), cancelled);
                MinecraftServer.getServer().execute(() -> {
                    if (player.connection != null) {
                        String result = failed.get() ? "stopped with an error" : cancelled.get() ? "cancelled" : "completed";
                        player.getBukkitEntity().sendActionBar(Component.text("Servux Litematica " + displayName + " task " + result,
                                failed.get() ? NamedTextColor.RED : NamedTextColor.GREEN));
                    }
                });
            }
        }
    }

    private static void reply(ServerPlayer player, String message) {
        player.getBukkitEntity().sendActionBar(Component.text(message, NamedTextColor.YELLOW));
    }
}
