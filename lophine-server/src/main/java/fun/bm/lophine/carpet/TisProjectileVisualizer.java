// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TisProjectileVisualizer {
    public static final String TAG = "##TISCM_VISPROJ_LOGGER##";
    private static final ThreadLocal<Entity> CONSTRUCTING = new ThreadLocal<>();
    private static final Map<UUID, Entity> VISUALIZERS = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();

    private TisProjectileVisualizer() {
    }

    public static void constructing(Entity entity) {
        if (CarpetLoggerProtocol.hasSubscribers("projectiles")) CONSTRUCTING.set(entity);
        else CONSTRUCTING.remove();
    }

    public static Entity takeEntity(String logger) {
        if (!"projectiles".equals(logger)) return null;
        Entity entity = CONSTRUCTING.get();
        CONSTRUCTING.remove();
        return entity;
    }

    public static void bind(Entity entity, CarpetTrajectoryLogger logger) {
        if (entity != null) entity.carpetTisTrajectory = logger;
    }

    public static boolean isVisualizer(Entity entity) {
        return entity instanceof Snowball && entity.entityTags().contains(TAG);
    }

    public static void hit(Projectile entity, HitResult result) {
        if (entity.carpetTisTrajectory != null) entity.carpetTisTrajectory.hit(result);
    }

    public static boolean tick(Snowball snowball) {
        if (!isVisualizer(snowball)) return false;
        if (CarpetLoggerProtocol.hasSubscribers("projectiles")) {
            if (snowball.getDeltaMovement().lengthSqr() > 0.0) {
                snowball.needsSync = true;
                snowball.setDeltaMovement(Vec3.ZERO);
            }
            VISUALIZERS.put(snowball.getUUID(), snowball);
        } else snowball.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        return true;
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        var entities = List.copyOf(VISUALIZERS.values());
        VISUALIZERS.clear();
        for (Entity entity : entities)
            entity.getBukkitEntity().taskScheduler.schedule(owner -> {
                if (!owner.isRemoved()) owner.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
            }, null, 1L);
    }

    public static void reset() {
        clear();
        CONSTRUCTING.remove();
    }

    public static void visualize(ServerLevel world, List<Vec3> positions, Vec3 hit) {
        long generation = GENERATION.get();
        Map<Long, List<NamedPosition>> chunks = new java.util.LinkedHashMap<>();
        for (int i = 0; i < positions.size(); ++i) {
            Vec3 point = positions.get(i);
            chunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(BlockPos.containing(point)), key -> new ArrayList<>())
                    .add(new NamedPosition(point, Integer.toString(i)));
        }
        if (hit != null)
            chunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(BlockPos.containing(hit)), key -> new ArrayList<>()).add(new NamedPosition(hit, "Hit"));
        for (var entry : chunks.entrySet()) {
            var chunk = net.minecraft.world.level.ChunkPos.unpack(entry.getKey());
            List<NamedPosition> points = List.copyOf(entry.getValue());
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(world, chunk.x(), chunk.z(), () -> {
                if (GENERATION.get() != generation) return;
                world.getChunk(chunk.x(), chunk.z());
                for (var point : points) {
                    if (GENERATION.get() != generation) break;
                    Snowball marker = new Snowball(world, point.position.x, point.position.y, point.position.z, new ItemStack(Items.SNOWBALL));
                    marker.setNoGravity(true);
                    marker.setCustomName(Component.literal(point.name));
                    marker.setCustomNameVisible(true);
                    marker.addTag(TAG);
                    if (world.addFreshEntity(marker)) VISUALIZERS.put(marker.getUUID(), marker);
                }
            });
        }
    }

    private record NamedPosition(Vec3 position, String name) {
    }

    public record Hit(Vec3 position, String kind, String target) {
        public static Hit capture(HitResult result) {
            if (result instanceof BlockHitResult block)
                return new Hit(result.getLocation(), "block", block.getBlockPos().toShortString());
            if (result instanceof EntityHitResult entity)
                return new Hit(result.getLocation(), "entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString());
            return new Hit(result.getLocation(), "?", "");
        }

        public String text() {
            return "Hit: " + kind + " " + target + "\n" + CarpetTrajectoryLogger.coordinates(position, true);
        }
    }
}
