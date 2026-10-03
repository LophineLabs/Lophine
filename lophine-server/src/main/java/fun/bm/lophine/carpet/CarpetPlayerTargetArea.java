package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Retains a bounded full-chunk footprint until native bot actions can run on one real owner. */
public final class CarpetPlayerTargetArea implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();
    private static final TicketType<Long> HOLD = ChunkSystemTicketType.create("carpet:player_target_area", Long::compare, 0L, TicketType.FLAG_LOADING);
    private static final Set<CarpetPlayerTargetArea> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean shuttingDown;
    private Scope current;
    private long retryAfter;

    /** The caller retries on its next real owner tick without consuming an action attempt. */
    static final class Pending extends RuntimeException {
        Pending() { super("Player action footprint is not owned and loaded yet", null, false, false); }
    }

    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        static Bounds query(AABB box) {
            // Exact extra chunk search used by native Moonrise EntityLookup.getEntities.
            return new Bounds((Mth.floor(box.minX) - 2) >> 4, (Mth.floor(box.minZ) - 2) >> 4,
                (Mth.floor(box.maxX) + 2) >> 4, (Mth.floor(box.maxZ) + 2) >> 4);
        }
        boolean owns(ServerLevel world) { return TickThread.isTickThreadFor(world, minX, minZ, maxX, maxZ); }
        boolean loaded(ServerLevel world) {
            if (!owns(world)) return false;
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) if (world.getChunkIfLoaded(x, z) == null) return false;
            return true;
        }
    }

    public synchronized boolean ready(ServerPlayer player, AABB box) {
        TickThread.ensureTickThread(player, "Player target area must be sampled on the player owner");
        if (shuttingDown) { close(); return false; }
        ServerLevel world = player.level();
        Bounds wanted = Bounds.query(box);
        if (current != null && current.failed && current.acquired) {
            close(); retryAfter = System.nanoTime() + 5_000_000_000L;
        }
        if (System.nanoTime() < retryAfter) return false;
        if (current != null && (current.world != world || !current.bounds.equals(wanted))) close();
        if (current == null) {
            current = new Scope(world, wanted);
            ACTIVE.add(this);
            current.start();
        }
        return current.bounds.loaded(world);
    }

    /** A hand fallback keeps the exact click world and footprint captured before native deferral. */
    synchronized void requireCaptured(ServerPlayer player, ServerLevel world) {
        TickThread.ensureTickThread(player, "Player action continuation must own its player");
        if (shuttingDown || player.level() != world || current == null || current.world != world
            || current.cancelled || !current.bounds.loaded(world)) throw new Pending();
    }

    public static void closeAtShutdown(MinecraftServer server) {
        shuttingDown = true;
        for (var area : ACTIVE) {
            synchronized (area) { if (area.current != null && area.current.world.getServer() == server) area.close(); }
        }
    }

    @Override public synchronized void close() {
        if (current != null) { current.cancel(); current = null; }
        retryAfter = 0L;
        ACTIVE.remove(this);
    }

    private static final class Scope {
        final ServerLevel world;
        final Bounds bounds;
        final long id = IDS.incrementAndGet();
        final Set<Long> held = ConcurrentHashMap.newKeySet();
        volatile boolean cancelled;
        volatile boolean acquired;
        volatile boolean failed;
        Scope(ServerLevel world, Bounds bounds) { this.world = world; this.bounds = bounds; }

        void start() { Thread.ofVirtual().name("Carpet player target chunks").start(this::acquire); }
        void acquire() {
            try {
                var manager = world.moonrise$getChunkTaskScheduler().chunkHolderManager;
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    if (cancelled) return;
                    held.add(ChunkPos.pack(x, z));
                    manager.addTicketAtLevel(HOLD, x, z, 33, id);
                }
            } catch (Throwable failure) {
                cancelled = true;
                failed = true;
                MinecraftServer.LOGGER.error("Could not acquire Carpet player target chunks", failure);
            } finally {
                acquired = true;
                if (cancelled) release();
            }
        }
        void cancel() {
            cancelled = true;
            // Acquisition owns its finally. Nothing waits for a ticket lock on the tick thread.
            if (acquired) Thread.ofVirtual().name("Carpet player target release").start(this::release);
        }
        void release() {
            var manager = world.moonrise$getChunkTaskScheduler().chunkHolderManager;
            for (long key : held) {
                if (!held.remove(key)) continue;
                try { manager.removeTicketAtLevel(HOLD, key, 33, id); }
                catch (Throwable failure) { MinecraftServer.LOGGER.error("Could not release Carpet player target chunk " + key, failure); }
            }
        }
    }
}
