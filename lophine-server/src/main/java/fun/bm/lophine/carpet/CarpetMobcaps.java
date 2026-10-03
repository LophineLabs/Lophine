// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

/** Region owners publish values, so readers never access another region's SpawnState. */
public final class CarpetMobcaps {
    private static final Map<RegionKey, Snapshot> REGIONS = new ConcurrentHashMap<>();
    private static volatile Map<String, Snapshot> dimensions = Map.of();
    private CarpetMobcaps() {}
    private record RegionKey(String dimension, long region) {}
    public record Snapshot(int chunks, Map<MobCategory, Integer> counts, Map<MobCategory, Integer> limits, boolean global) {
        public Snapshot { counts = Map.copyOf(counts); limits = Map.copyOf(limits); }
    }
    public static void publish(ServerLevel level, NaturalSpawner.SpawnState state) {
        var region = TickRegionScheduler.getCurrentRegion();
        if (region == null || state == null) return;
        var counts = new EnumMap<MobCategory, Integer>(MobCategory.class);
        var limits = new EnumMap<MobCategory, Integer>(MobCategory.class);
        for (var category : MobCategory.values()) {
            counts.put(category, state.getMobCategoryCounts().getInt(category));
            limits.put(category, NaturalSpawner.globalLimitForCategory(level, category, state.getSpawnableChunkCount()));
        }
        REGIONS.put(new RegionKey(level.dimension().identifier().toString(), region.id), new Snapshot(state.getSpawnableChunkCount(), counts, limits, fun.bm.lophine.config.modules.experiment.GlobalEntitiesCounter.isEnabled() && !level.paperConfig().entities.spawning.perPlayerMobSpawns));
    }
    public static void globalTick(MinecraftServer server) {
        Set<RegionKey> active = new HashSet<>();
        for (var level : server.getAllLevels()) {
            String dimension = level.dimension().identifier().toString();
            level.regioniser.computeForAllRegions(region -> active.add(new RegionKey(dimension, region.id)));
        }
        REGIONS.keySet().removeIf(key -> !active.contains(key));
        Map<String, Snapshot> next = new java.util.HashMap<>();
        REGIONS.forEach((key, snapshot) -> next.merge(key.dimension, snapshot, (a, b) -> {
            boolean globalCounts = a.global && b.global;
            var counts = new EnumMap<MobCategory, Integer>(MobCategory.class);
            var limits = new EnumMap<MobCategory, Integer>(MobCategory.class);
            for (var category : MobCategory.values()) {
                counts.put(category, globalCounts ? Math.max(a.counts.getOrDefault(category, 0), b.counts.getOrDefault(category, 0))
                    : a.counts.getOrDefault(category, 0) + b.counts.getOrDefault(category, 0));
                limits.put(category, a.limits.getOrDefault(category, 0) < 0 ? -1 : globalCounts ? Math.max(a.limits.getOrDefault(category, 0), b.limits.getOrDefault(category, 0))
                    : a.limits.getOrDefault(category, 0) + b.limits.getOrDefault(category, 0));
            }
            return new Snapshot(globalCounts ? Math.max(a.chunks, b.chunks) : a.chunks + b.chunks, counts, limits, globalCounts);
        }));
        dimensions = Map.copyOf(next);
    }
    public static Snapshot dimension(String dimension) { return dimensions.get(dimension); }
    public static Map<String, Snapshot> dimensions() { return dimensions; }
    public static void reset() { REGIONS.clear(); dimensions = Map.of(); }
}
