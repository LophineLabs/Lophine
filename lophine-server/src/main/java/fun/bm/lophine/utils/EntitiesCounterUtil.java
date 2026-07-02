package fun.bm.lophine.utils;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.PositionCountingAreaMap;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.config.modules.experiment.GlobalEntitiesCounter;
import fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EntitiesCounterUtil {
    // global entities counter
    private static final Map<ServerLevel, Cache<Integer, ReferenceList<Entity>>> globalLoadedEntities = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Object2IntOpenHashMap<MobCategory>> mobsMap = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerLevel, Cache<Integer, PositionCountingAreaMap<ServerPlayer>>> mobsAreaMap = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Integer> spawnableChunkCount = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
    private static final Set<Integer> UniqueIds = ConcurrentHashMap.newKeySet();

    // precise mob counter extra
    private static final Map<ServerLevel, EnumMap<MobCategory, AtomicInteger>> globalMobCounts = new ConcurrentHashMap<>();

    private static int lastUsedId = 0;

    private static final int CLEANUP_INTERVAL = 200; // each 200 ids used

    public static int generateUniqueId() {
        synchronized (UniqueIds) {
            if (lastUsedId % CLEANUP_INTERVAL == 0) runCleanUp();
            int id = lastUsedId;
            while (UniqueIds.contains(id)) {
                id++;
            }

            lastUsedId = id;
            UniqueIds.add(id);
            return id;
        }
    }

    public static void onWorldDataUnload(ServerLevel level, int uniqueId) {
        UniqueIds.remove(uniqueId);
        Cache<Integer, ReferenceList<Entity>> entitiesCache = globalLoadedEntities.get(level);
        if (entitiesCache != null) {
            entitiesCache.invalidate(uniqueId);
        }

        Cache<Integer, PositionCountingAreaMap<ServerPlayer>> areaCache = mobsAreaMap.get(level);
        if (areaCache != null) {
            areaCache.invalidate(uniqueId);
        }
    }

    private static void runCleanUp() {
        Set<Integer> logged = new HashSet<>();
        for (Cache<Integer, ReferenceList<Entity>> collection : globalLoadedEntities.values()) {
            logged.addAll(collection.asMap().keySet());
        }

        for (int num : UniqueIds) {
            if (logged.contains(num)) continue;
            UniqueIds.remove(num);
        }
    }

    public static void addDataToLoaded(ServerLevel level, ReferenceList<Entity> data, int uniqueId) {
        Cache<Integer, ReferenceList<Entity>> data0 = globalLoadedEntities.computeIfAbsent(level, _ -> CacheBuilder.newBuilder().concurrencyLevel(16).weakValues().build());
        if (data0.asMap().containsKey(uniqueId)) return;
        data0.put(uniqueId, data);
    }

    public static void reportAreaMap(ServerLevel level, PositionCountingAreaMap<ServerPlayer> areaMap, int uniqueId) {
        Cache<Integer, PositionCountingAreaMap<ServerPlayer>> areaMap0 = mobsAreaMap.computeIfAbsent(level, _ -> CacheBuilder.newBuilder().concurrencyLevel(16).weakValues().build());
        if (areaMap0.asMap().containsKey(uniqueId)) return;
        areaMap0.put(uniqueId, areaMap);
    }

    public static int getTotalChunkCount(ServerLevel level) {
        return spawnableChunkCount.getOrDefault(level, 0);
    }

    public static @Nullable Object2IntOpenHashMap<MobCategory> getMobsMap(ServerLevel level) {
        return mobsMap.get(level);
    }

    public static boolean canRunNewTask(ServerLevel level) {
        CompletableFuture<Void> task = tasks.get(level);
        return task == null || task.isDone();
    }

    public static void tick(ServerLevel level) {
        if (GlobalEntitiesCounter.isDefaultModule()) {
            Runnable task = () -> {
                try {
                    Cache<Integer, ReferenceList<Entity>> data0 = globalLoadedEntities.get(level);
                    if (data0 == null) return;

                    Object2IntOpenHashMap<MobCategory> map = new Object2IntOpenHashMap<>();
                    Collection<ReferenceList<Entity>> snapshot = data0.asMap().values();

                    for (ReferenceList<Entity> data : snapshot) {
                        if (data == null) continue;
                        for (Entity entity : GlobalEntitiesCounter.isAsync() ? data.copy() : data) {
                            if (entity == null || entity.isRemoved() || !entity.isAlive()) continue;
                            // Lophine start - Copy from net/minecraft/world/level/NaturalSpawner
                            MobCategory category = entity.getType().getCategory();
                            if (category != MobCategory.MISC) {
                                // Paper start - Only count natural spawns
                                if (!entity.level().paperConfig().entities.spawning.countAllMobsForSpawning &&
                                        !(entity.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL ||
                                                entity.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN)) {
                                    continue;
                                }
                                // Paper end - Only count natural spawns
                                map.addTo(category, 1);
                            }
                        }
                        // Lophine end - Copy from net/minecraft/world/level/NaturalSpawner
                    }
                    mobsMap.put(level, map);

                    Cache<Integer, PositionCountingAreaMap<ServerPlayer>> collection = mobsAreaMap.get(level);
                    if (collection != null) {
                        int count = 0;
                        for (PositionCountingAreaMap<ServerPlayer> areaMap : collection.asMap().values()) {
                            if (areaMap != null) {
                                count += areaMap.getTotalPositions();
                            }
                        }
                        spawnableChunkCount.put(level, count);
                    }
                } catch (Exception e) {
                    LogUtils.getClassLogger().error("Failed to run task", e);
                }
            };
            if (GlobalEntitiesCounter.isAsync()) {
                tasks.put(level, CompletableFuture.runAsync(task).exceptionally(ex -> {
                    LogUtils.getClassLogger().error("Failed to run task", ex);
                    return null;
                }));
            } else {
                task.run();
            }
        } else {
            Cache<Integer, PositionCountingAreaMap<ServerPlayer>> cache = mobsAreaMap.get(level);
            if (cache == null) return;

            int total = 0;
            for (PositionCountingAreaMap<ServerPlayer> map : cache.asMap().values()) {
                if (map != null) {
                    total += map.getTotalPositions();
                }
            }

            spawnableChunkCount.put(level, total);
        }
    }

    public static NaturalSpawner.SpawnState createSpawnState(
            ServerLevel level, Iterable<Entity> entities, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator calculator, final boolean countMobs
    ) {
        if (GlobalEntitiesCounter.isDefaultModule()) {
            Object2IntOpenHashMap<MobCategory> map = getMobsMap(level);
            if (map == null) return null; // skip if no data
            // Lophine start - Copy from net/minecraft/world/level/NaturalSpawner
            PotentialCalculator potentialCalculator = new PotentialCalculator();
            for (Entity entity : entities) {
                if (entity == null || entity.isRemoved() || !entity.isAlive()) continue;
                // Paper start - Only count natural spawns
                if (!entity.level().paperConfig().entities.spawning.countAllMobsForSpawning &&
                        !(entity.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL ||
                                entity.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN)) {
                    continue;
                }
                // Paper end - Only count natural spawns
                BlockPos blockPos = entity.blockPosition();
                chunkGetter.query(ChunkPos.pack(blockPos), chunk -> {
                    MobSpawnSettings.MobSpawnCost mobSpawnCost = NaturalSpawner.getRoughBiome(blockPos, chunk).getMobSettings().getMobSpawnCost(entity.getType());
                    if (mobSpawnCost != null) {
                        potentialCalculator.addCharge(entity.blockPosition(), mobSpawnCost.charge());
                    }

                    if (calculator != null && entity instanceof Mob) { // Paper - Optional per player mob spawns
                        calculator.addMob(chunk.getPos(), entity.getType().getCategory());
                    }

                    // Paper start - Optional per player mob spawns
                    if (countMobs) {
                        chunk.level.getChunkSource().chunkMap.updatePlayerMobTypeMap(entity);
                    }
                    // Paper end - Optional per player mob spawns
                });
            }
            return new NaturalSpawner.SpawnState(getTotalChunkCount(level), map, potentialCalculator, calculator);
            // Lophine end - Copy from net/minecraft/world/level/NaturalSpawner
        } else {
            int chunkCount = getSpawnableChunkCount(level);
            if (chunkCount == 0) return null;

            Object2IntOpenHashMap<MobCategory> globalCounts = getGlobalCounts(level);

            PotentialCalculator potentialCalculator = new PotentialCalculator();
            for (Entity entity : entities) {
                if (entity == null || entity.isRemoved() || !entity.isAlive()) continue;
                if (!shouldCount(entity)) continue;

                BlockPos pos = entity.blockPosition();
                chunkGetter.query(ChunkPos.pack(pos), chunk -> {
                    MobSpawnSettings.MobSpawnCost mobSpawnCost = NaturalSpawner.getRoughBiome(pos, chunk).getMobSettings().getMobSpawnCost(entity.getType());
                    if (mobSpawnCost != null) {
                        potentialCalculator.addCharge(entity.blockPosition(), mobSpawnCost.charge());
                    }

                    if (calculator != null && entity instanceof Mob) {
                        calculator.addMob(chunk.getPos(), entity.getType().getCategory());
                    }
                });
            }

            return new NaturalSpawner.SpawnState(chunkCount, globalCounts, potentialCalculator, calculator);
        }
    }

    public static Object2IntOpenHashMap<MobCategory> getGlobalCounts(ServerLevel level) {
        EnumMap<MobCategory, AtomicInteger> counts = globalMobCounts.get(level);
        Object2IntOpenHashMap<MobCategory> result = new Object2IntOpenHashMap<>();
        if (counts != null) {
            for (Map.Entry<MobCategory, AtomicInteger> entry : counts.entrySet()) {
                result.put(entry.getKey(), entry.getValue().get());
            }
        }
        return result;
    }

    public static int getSpawnableChunkCount(ServerLevel level) {
        Integer value = spawnableChunkCount.get(level);
        return value != null ? value : 0;
    }

    public static void onWorldUnload(ServerLevel level) {
        globalMobCounts.remove(level);
        mobsAreaMap.remove(level);
        spawnableChunkCount.remove(level);
    }

    public static void onEntityTrackingStart(ServerLevel level, Entity entity) {
        if (!shouldCount(entity)) return;
        MobCategory category = entity.getType().getCategory();
        getOrCreateCounts(level).computeIfAbsent(category, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public static boolean shouldCount(Entity entity) {
        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MISC) return false;

        if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
            return false;
        }

        if (VanillaLikeExperienceConfig.enabled || entity.level().paperConfig().entities.spawning.countAllMobsForSpawning) {
            return true;
        }

        return entity.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL
                || entity.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    private static EnumMap<MobCategory, AtomicInteger> getOrCreateCounts(ServerLevel level) {
        return globalMobCounts.computeIfAbsent(level, _ -> new EnumMap<>(MobCategory.class));
    }

    public static void onEntityTrackingEnd(ServerLevel level, Entity entity) {
        if (!shouldCount(entity)) return;
        MobCategory category = entity.getType().getCategory();
        EnumMap<MobCategory, AtomicInteger> counts = getOrCreateCounts(level);
        AtomicInteger counter = counts.get(category);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }
}
