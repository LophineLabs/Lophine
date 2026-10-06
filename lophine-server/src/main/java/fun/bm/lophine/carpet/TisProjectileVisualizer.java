// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class TisProjectileVisualizer {
    public static final String TAG = "##TISCM_VISPROJ_LOGGER##";
    private static final ThreadLocal<Entity> CONSTRUCTING = new ThreadLocal<>();
    private static final Map<UUID, Marker> VISUALIZERS = new ConcurrentHashMap<>();
    private static final carpet.script.external.WeakIdentityMap<Entity, Long> MARKER_GENERATIONS = new carpet.script.external.WeakIdentityMap<>();
    private static final carpet.script.external.WeakIdentityMap<MinecraftServer, Boolean> CLOSING_SERVERS = new carpet.script.external.WeakIdentityMap<>();
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
        snowball.persist = false;
        Long generation = MARKER_GENERATIONS.get(snowball);
        MinecraftServer server = server(snowball);
        if (generation != null && generation == GENERATION.get() && !closing(server) && CarpetLoggerProtocol.hasSubscribers("projectiles")) {
            if (snowball.getDeltaMovement().lengthSqr() > 0.0) {
                snowball.needsSync = true;
                snowball.setDeltaMovement(Vec3.ZERO);
            }
            publish(snowball, generation);
        } else {
            remove(snowball);
            snowball.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        }
        return true;
    }

    public static void clear() {
        long generation = GENERATION.incrementAndGet();
        for (Marker marker : List.copyOf(VISUALIZERS.values())) {
            if (marker.generation >= generation || !VISUALIZERS.remove(marker.entity.getUUID(), marker)) continue;
            discard(marker);
        }
    }

    /** Initiate before the server's native drain, while owner schedulers are still running. */
    public static void clearAtShutdown(MinecraftServer server) {
        if (CLOSING_SERVERS.put(server, Boolean.TRUE) != null) return;
        for (Marker marker : List.copyOf(VISUALIZERS.values())) {
            if (marker.server == server && VISUALIZERS.remove(marker.entity.getUUID(), marker)) discard(marker);
        }
    }

    public static void reset() {
        clear();
        CONSTRUCTING.remove();
    }

    /** Removal metadata only; never inspect or write a foreign marker's live state. */
    public static void removed(Entity entity) {
        if (!VISUALIZERS.isEmpty()) remove(entity);
    }

    public static void visualize(ServerLevel world, List<Vec3> positions, Vec3 hit) {
        if (closing(world.getServer())) return;
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
                if (GENERATION.get() != generation || closing(world.getServer())) return;
                world.getChunk(chunk.x(), chunk.z());
                for (var point : points) {
                    if (GENERATION.get() != generation) break;
                    Snowball marker = new Snowball(world, point.position.x, point.position.y, point.position.z, new ItemStack(Items.SNOWBALL));
                    marker.persist = false;
                    marker.setNoGravity(true);
                    marker.setCustomName(Component.literal(point.name));
                    marker.setCustomNameVisible(true);
                    marker.addTag(TAG);
                    MARKER_GENERATIONS.put(marker, generation);
                    if (world.addFreshEntity(marker)) publish(marker, generation);
                }
            });
        }
    }

    private record NamedPosition(Vec3 position, String name) {
    }

    private static final class Marker {
        final Entity entity;
        final long generation;
        final MinecraftServer server;

        Marker(Entity entity, long generation, MinecraftServer server) {
            this.entity = entity;
            this.generation = generation;
            this.server = server;
        }
    }

    private static void remove(Entity entity) {
        VISUALIZERS.computeIfPresent(entity.getUUID(), (id, marker) -> marker.entity == entity ? null : marker);
    }

    /** Called by the marker's owner, including after the add-entity callback returns. */
    private static void publish(Entity entity, long generation) {
        MinecraftServer server = server(entity);
        if (generation != GENERATION.get() || closing(server)) {
            remove(entity);
            entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
            return;
        }
        Marker marker = new Marker(entity, generation, server);
        VISUALIZERS.put(entity.getUUID(), marker);
        // Clear may have run after the preflight check, while an add/tick callback
        // was still executing. Its old marker must neither survive nor rejoin.
        if (generation != GENERATION.get() || closing(server)) {
            VISUALIZERS.remove(entity.getUUID(), marker);
            entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        }
    }

    private static MinecraftServer server(Entity entity) {
        return entity.level() instanceof ServerLevel world ? world.getServer() : null;
    }

    private static boolean closing(MinecraftServer server) {
        return server != null && (CLOSING_SERVERS.get(server) != null || carpet.script.external.ScarpetNativeWork.isDraining(server));
    }

    private static void discard(Marker marker) {
        var actual = new CompletableFuture<Void>();
        carpet.script.external.ScarpetNativeWork.record(actual);
        if (marker.server != null) carpet.script.external.ScarpetNativeWork.trackNative(marker.server, actual);
        try {
            var captured = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() ->
                    carpet.script.external.ScarpetNativeWork.<Void>observeNative(marker.entity, () -> {
                        if (!marker.entity.isRemoved()) marker.entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
                        return null;
                    }));
            boolean scheduled = marker.entity.getBukkitEntity().taskScheduler.schedule(owner -> {
                try {
                    if (owner != marker.entity) throw new IllegalStateException("Projectile marker owner changed");
                    var observed = captured.get();
                    carpet.script.external.ScarpetNativeWork.aliasDependency(actual, observed);
                    observed.whenComplete((ignored, failure) -> {
                        if (failure == null) actual.complete(null);
                        else actual.completeExceptionally(failure);
                    });
                } catch (Throwable failure) { actual.completeExceptionally(failure); }
            }, retired -> actual.complete(null), 1L);
            if (!scheduled) actual.complete(null);
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
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
