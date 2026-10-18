// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import fun.bm.lophine.config.modules.experiment.GlobalEntitiesCounter;
import fun.bm.lophine.utils.EntitiesCounterUtil;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region owners publish values, so readers never access another region's SpawnState.
 * Default reads are the current region's snapshot. GlobalEntitiesCounter switches HUD and Scarpet to that module.
 */
public final class CarpetMobcaps {
    private static ThreadLocal<Snapshot> SNAPSHOT = new ThreadLocal<>();
    private static Map<String, Snapshot> LEVELMAP = new ConcurrentHashMap<>();

    private CarpetMobcaps() {
    }

    private record RegionKey(String dimension, long region) {
    }

    public record Snapshot(int chunks, Map<MobCategory, Integer> counts, Map<MobCategory, Integer> limits,
                           boolean global) {
        public Snapshot {
            counts = Map.copyOf(counts);
            limits = Map.copyOf(limits);
        }
    }

    public static void publish(ServerLevel level, NaturalSpawner.SpawnState state) {
        if (GlobalEntitiesCounter.isEnabled()) return; // is global count enabled, skip all process
        var region = TickRegionScheduler.getCurrentRegion();
        if (region == null || state == null) return;
        var counts = new EnumMap<MobCategory, Integer>(MobCategory.class);
        var limits = new EnumMap<MobCategory, Integer>(MobCategory.class);
        for (var category : MobCategory.values()) {
            counts.put(category, state.getMobCategoryCounts().getInt(category));
            limits.put(category, NaturalSpawner.globalLimitForCategory(level, category, state.getSpawnableChunkCount()));
        }
        SNAPSHOT.set(new Snapshot(state.getSpawnableChunkCount(), counts, limits, fun.bm.lophine.config.modules.experiment.GlobalEntitiesCounter.isEnabled() && !level.paperConfig().entities.spawning.perPlayerMobSpawns));
    }

    public static void globalTick(MinecraftServer server) {
        if (GlobalEntitiesCounter.isEnabled()) {
            LEVELMAP = snapshotsFromGlobalCounter(server);
        }
    }

    private static Map<String, Snapshot> snapshotsFromGlobalCounter(MinecraftServer server) {
        Map<String, Snapshot> next = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            Object2IntOpenHashMap<MobCategory> map = GlobalEntitiesCounter.isDefaultModule()
                    ? EntitiesCounterUtil.getMobsMap(level)
                    : EntitiesCounterUtil.getGlobalCounts(level);
            if (map == null) continue;
            int chunks = EntitiesCounterUtil.getSpawnableChunkCount(level);
            var counts = new EnumMap<MobCategory, Integer>(MobCategory.class);
            var limits = new EnumMap<MobCategory, Integer>(MobCategory.class);
            for (var category : MobCategory.values()) {
                counts.put(category, map.getInt(category));
                limits.put(category, NaturalSpawner.globalLimitForCategory(level, category, chunks));
            }
            next.put(level.dimension().identifier().toString(), new Snapshot(chunks, counts, limits, true));
        }
        return Map.copyOf(next);
    }

    public static Snapshot globalOrElseLocal(ServerLevel level) {
        if (level == null) return null;
        if (GlobalEntitiesCounter.isEnabled()) {
            return LEVELMAP.get(level.dimension().identifier().toString());
        }
        return SNAPSHOT.get();
    }

    public static void reset() {
        SNAPSHOT = new ThreadLocal<>();
        LEVELMAP = Map.of();
    }
}
