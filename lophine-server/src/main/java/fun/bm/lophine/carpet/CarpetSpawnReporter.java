// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Natural-spawn instrumentation is confined to each regional spawn cycle. */
public final class CarpetSpawnReporter {
    private static final Map<MobCategory, Integer> ROUNDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Cycle> CYCLE = new ThreadLocal<>();
    private static volatile Session session;
    public static volatile boolean mockSpawns;
    private static volatile double capScale = 1.0;
    static { resetRates(); }
    private CarpetSpawnReporter() {}
    private record Key(String dimension, MobCategory category) {}
    private record Recent(String type, BlockPos pos) {}
    private static final class Stats {
        final LongAdder ticks = new LongAdder(), attempts = new LongAdder(), cap = new LongAdder(), full = new LongAdder(), failed = new LongAdder(), succeeded = new LongAdder(), spawned = new LongAdder();
        final Map<String, LongAdder> types = new ConcurrentHashMap<>();
        final ArrayDeque<Recent> recent = new ArrayDeque<>();
        synchronized void addRecent(Recent value) { recent.remove(value); recent.addLast(value); if (recent.size() > 10) recent.removeFirst(); }
        synchronized List<Recent> recent() { return List.copyOf(recent); }
    }
    private static final class Session {
        final long start = CarpetServerClock.gameTime();
        final BoundingBox area;
        final Map<Key, Stats> stats = new ConcurrentHashMap<>();
        Session(BoundingBox area) { this.area = area; }
        Stats stats(String dimension, MobCategory category) { return stats.computeIfAbsent(new Key(dimension, category), ignored -> new Stats()); }
    }
    private static final class Cycle {
        final Session tracking;
        final String dimension;
        final EnumMap<MobCategory, Long> spawned = new EnumMap<>(MobCategory.class);
        final EnumMap<MobCategory, Integer> rounds = new EnumMap<>(MobCategory.class);
        final java.util.EnumSet<MobCategory> tried = java.util.EnumSet.noneOf(MobCategory.class);
        Cycle(Session tracking, String dimension) { this.tracking = tracking; this.dimension = dimension; for (var category : MobCategory.values()) rounds.put(category, CarpetSpawnReporter.rounds(category)); }
    }
    public static int rounds(MobCategory category) { return ROUNDS.getOrDefault(category, 1); }
    public static void setRounds(MobCategory category, int rounds) { if (rounds < 0) throw new IllegalArgumentException("Expected nonnegative rounds"); ROUNDS.put(category, rounds); }
    public static void resetRates() { for (var category : MobCategory.values()) ROUNDS.put(category, 1); }
    public static int scaleCap(int cap) { return cap < 0 ? cap : (int) Math.min(Integer.MAX_VALUE, cap * capScale); }
    public static void setHostileCap(int cap) { capScale = cap / 70.0; }
    public static boolean tracking() { return session != null; }
    public static synchronized boolean start(BoundingBox area) { if (session != null) return false; session = new Session(area); return true; }
    public static synchronized void restart(BoundingBox area) { session = new Session(area); }
    public static synchronized List<Component> stop() { List<Component> result = report(); session = null; return result; }
    public static void beginCycle(ServerLevel level, NaturalSpawner.SpawnState state) {
        CYCLE.remove();
        Session tracking = session;
        if (tracking == null || state == null) return;
        Cycle cycle = new Cycle(tracking, level.dimension().identifier().toString());
        CYCLE.set(cycle);
        for (var category : MobCategory.values()) {
            Stats stats = tracking.stats(cycle.dimension, category);
            int rounds = cycle.rounds.get(category);
            stats.ticks.add(rounds);
            stats.cap.add(state.getMobCategoryCounts().getInt(category));
        }
    }
    public static void attempted(MobCategory category) { Cycle cycle = CYCLE.get(); if (cycle != null) cycle.tried.add(category); }
    public static void spawned(Mob mob, MobCategory category, BlockPos pos) {
        Cycle cycle = CYCLE.get();
        if (cycle == null || cycle.tracking.area != null && !cycle.tracking.area.isInside(pos)) return;
        cycle.spawned.merge(category, 1L, Long::sum);
        Stats stats = cycle.tracking.stats(cycle.dimension, category);
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        stats.types.computeIfAbsent(type, ignored -> new LongAdder()).increment();
        stats.addRecent(new Recent(type, pos.immutable()));
    }
    public static void finishCycle(ServerLevel level, NaturalSpawner.SpawnState state) {
        try {
            CarpetMobcaps.publish(level, state);
            Cycle cycle = CYCLE.get();
            if (cycle == null) return;
            for (var category : MobCategory.values()) {
                Stats stats = cycle.tracking.stats(cycle.dimension, category);
                int rounds = cycle.rounds.get(category);
                if (!cycle.tried.contains(category)) { if (!category.isPersistent() || level.getGameTime() % 400L == 0L) stats.full.add(rounds); }
                else {
                    stats.attempts.add(rounds);
                    long count = cycle.spawned.getOrDefault(category, 0L);
                    if (count == 0) stats.failed.add(rounds);
                    else { stats.succeeded.add(rounds); stats.spawned.add(count); }
                }
            }
        } finally { CYCLE.remove(); }
    }
    public static List<Component> report() {
        Session tracking = session;
        if (tracking == null) return List.of(Component.literal("Spawn tracking is disabled; /spawn tracking start to enable"));
        long duration = Math.max(1, CarpetServerClock.gameTime() - tracking.start);
        List<Component> result = new ArrayList<>();
        result.add(Component.literal(String.format(Locale.ROOT, "%sSpawn statistics%s: %.1f min", mockSpawns ? "[SIMULATED] " : "", tracking.area == null ? "" : " in " + tracking.area, duration / 1200.0)));
        for (var entry : tracking.stats.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().dimension + "/" + e.getKey().category.getName())).toList()) {
            var key = entry.getKey(); var stats = entry.getValue();
            long attempted = stats.attempts.sum(), full = stats.full.sum(), failed = stats.failed.sum(), success = stats.succeeded.sum();
            long samples = attempted + full;
            if (samples == 0) continue;
            result.add(Component.literal(String.format(Locale.ROOT, " > %s %s: %.2f mobs/tick; %.1f%% full, %.1f%% failed, %.1f%% successful; %.2f spawns/attempt (%d regional ticks)",
                key.category.getName(), key.dimension, stats.cap.sum() / (double) Math.max(1, stats.ticks.sum()), 100.0 * full / samples, 100.0 * failed / samples, 100.0 * success / samples, stats.spawned.sum() / (double) Math.max(1, failed + success), stats.ticks.sum())));
            stats.types.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(type -> result.add(Component.literal("   - " + type.getKey() + ": " + type.getValue().sum() + " spawns, " + 72000L * type.getValue().sum() / duration + " per hour")));
        }
        return result;
    }
    public static List<Component> recent(ServerLevel level, MobCategory category) {
        Session tracking = session;
        if (tracking == null) return List.of(Component.literal("Spawn tracking not started"));
        var stats = tracking.stats.get(new Key(level.dimension().identifier().toString(), category));
        List<Component> output = new ArrayList<>(); output.add(Component.literal("Recent " + category.getName() + " spawns:"));
        if (stats != null) for (var recent : stats.recent()) output.add(Component.literal(" - " + recent.type + " " + recent.pos.toShortString()).withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/tp " + recent.pos.getX() + " " + recent.pos.getY() + " " + recent.pos.getZ()))));
        if (output.size() == 1) output.add(Component.literal(" - Nothing spawned yet"));
        return output;
    }
    public static void reset() { session = null; mockSpawns = false; capScale = 1.0; resetRates(); CYCLE.remove(); CarpetMobcaps.reset(); }
}
