/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class TisRaidCommand {
    private static final AtomicReference<Session> SESSION = new AtomicReference<>();

    private TisRaidCommand() {
    }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("raid", "", List.of(), false);
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        registerLogger();
        dispatcher.register(Commands.literal("raid")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandRaid))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), false))
                        .then(Commands.literal("full").executes(context -> list(context.getSource(), true))))
                .then(Commands.literal("tracking").executes(context -> report(context.getSource(), false))
                        .then(Commands.literal("start").executes(context -> start(context.getSource(), false)))
                        .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                        .then(Commands.literal("restart").executes(context -> start(context.getSource(), true)))
                        .then(Commands.literal("realtime").executes(context -> report(context.getSource(), true)))));
    }

    private static int list(final CommandSourceStack source, final boolean full) {
        List<CompletableFuture<List<String>>> results = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (var entry : level.getRaids().raidMap.entrySet()) {
                CompletableFuture<List<String>> result = new CompletableFuture<>();
                results.add(result);
                snapshotRaid(level, entry.getKey(), entry.getValue(), full, result);
            }
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                TisRaycastCommand.feedback(source, "Raid listing failed: " + failure.getMessage());
            } else if (results.isEmpty()) {
                TisRaycastCommand.feedback(source, TisTranslations.message(source, "command.raid.no_raid"));
            } else {
                for (CompletableFuture<List<String>> result : results) {
                    for (String line : result.join()) TisRaycastCommand.feedback(source, line);
                }
            }
        });
        return 1;
    }

    private static void snapshotRaid(final ServerLevel level, final int id, final Raid raid, final boolean full,
                                     final CompletableFuture<List<String>> result) {
        BlockPos center = raid.getCenter();
        RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(level, center.getX() >> 4, center.getZ() >> 4, () -> {
            try {
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, raid.getCenter())) {
                    snapshotRaid(level, id, raid, full, result);
                    return;
                }
                if (level.getRaids().get(id) != raid) {
                    result.complete(List.of());
                    return;
                }
                Raid.CarpetRaidView view = raid.carpetView();
                List<String> lines = new ArrayList<>();
                lines.add(level.dimension().identifier() + " - Raid #" + id + "; status " + view.status()
                        + "; center " + view.center().toShortString() + "; raid omen " + view.omenLevel()
                        + "; waves " + view.currentWave() + "/" + view.waveCount() + "; raiders " + view.raiders().size());
                List<CompletableFuture<String>> raiders = new ArrayList<>();
                for (Raider raider : view.raiders()) {
                    CompletableFuture<String> description = new CompletableFuture<>();
                    raiders.add(description);
                    boolean scheduled = raider.getBukkitEntity().taskScheduler.schedule(entity -> {
                        try {
                            description.complete((raider == view.captain() ? "[Captain] " : "") + raider.getDisplayName().getString()
                                    + " (" + raider.getStringUUID() + ") at " + raider.position());
                        } catch (Throwable failure) {
                            description.completeExceptionally(failure);
                        }
                    }, entity -> description.complete("[Removed raider]"), 1L);
                    if (!scheduled) description.complete("[Removed raider]");
                }
                CompletableFuture.allOf(raiders.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
                    if (failure != null) result.completeExceptionally(failure);
                    else {
                        if (full) for (var raider : raiders) lines.add(" - " + raider.join());
                        else if (!raiders.isEmpty())
                            lines.add(String.join(" | ", raiders.stream().map(CompletableFuture::join).toList()));
                        result.complete(List.copyOf(lines));
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    private static int start(final CommandSourceStack source, final boolean restart) {
        Session fresh = new Session(CarpetServerClock.gameTime(), System.currentTimeMillis());
        Session active;
        if (restart) {
            active = SESSION.getAndSet(fresh);
        } else if (SESSION.compareAndSet(null, fresh)) {
            active = null;
        } else {
            TisRaycastCommand.feedback(source, TisTranslations.message(source, "tracker.tracking_already_started", TisTranslations.text("tracker.tracker_name_full", TisTranslations.text("tracker.raid.name"))));
            return 0;
        }
        if (active != null) print(source, active, false);
        TisRaycastCommand.feedback(source, TisTranslations.message(source, restart ? "tracker.tracking_restarted" : "tracker.tracking_started", TisTranslations.text("tracker.tracker_name_full", TisTranslations.text("tracker.raid.name"))));
        return 1;
    }

    private static int stop(final CommandSourceStack source) {
        Session active = SESSION.getAndSet(null);
        if (active == null) {
            TisRaycastCommand.feedback(source, TisTranslations.message(source, "tracker.tracking_not_started", TisTranslations.text("tracker.tracker_name_full", TisTranslations.text("tracker.raid.name"))));
            return 0;
        }
        print(source, active, false);
        TisRaycastCommand.feedback(source, TisTranslations.message(source, "tracker.tracking_stopped", TisTranslations.text("tracker.tracker_name_full", TisTranslations.text("tracker.raid.name"))));
        return 1;
    }

    private static int report(final CommandSourceStack source, final boolean realtime) {
        Session active = SESSION.get();
        if (active == null)
            TisRaycastCommand.feedback(source, TisTranslations.message(source, "tracker.tracking_not_started", TisTranslations.text("tracker.tracker_name_full", TisTranslations.text("tracker.raid.name"))));
        else print(source, active, realtime);
        return 1;
    }

    private static void print(final CommandSourceStack source, final Session session, final boolean realtime) {
        long ticks = Math.max(1L, realtime ? (System.currentTimeMillis() - session.startMillis) / 50L
                : CarpetServerClock.gameTime() - session.startTick);
        Map<String, Long> raiders = snapshot(session.raiders), reasons = snapshot(session.invalidated);
        long totalRaiders = raiders.values().stream().mapToLong(Long::longValue).sum();
        long totalInvalidated = reasons.values().stream().mapToLong(Long::longValue).sum();
        TisRaycastCommand.feedback(source, "Raid tracker: " + ticks + " " + (realtime ? "real-time" : "in-game") + " ticks; generated "
                + rate(session.generated.get(), ticks) + "; raiders " + rate(totalRaiders, ticks));
        raiders.forEach((type, count) -> TisRaycastCommand.feedback(source,
                " - " + type + ": " + rate(count, ticks) + ", " + percent(count, totalRaiders)));
        if (reasons.isEmpty()) TisRaycastCommand.feedback(source, "Invalidation reasons: none");
        else reasons.forEach((reason, count) -> TisRaycastCommand.feedback(source,
                " - " + reason + ": " + rate(count, ticks) + ", " + percent(count, totalInvalidated)));
    }

    private static Map<String, Long> snapshot(final Map<String, LongAdder> counters) {
        Map<String, Long> values = new TreeMap<>();
        counters.forEach((key, value) -> values.put(key, value.sum()));
        return values;
    }

    private static String rate(final long count, final long ticks) {
        return String.format(java.util.Locale.ROOT, "%d (%.1f/h)", count, count * 72000.0 / ticks);
    }

    private static String percent(final long count, final long total) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", total == 0L ? 0.0 : count * 100.0 / total);
    }

    public static void stopAtShutdown() {
        SESSION.set(null);
    }

    public static void onGenerated() {
        Session active = SESSION.get();
        if (active != null) active.generated.incrementAndGet();
    }

    public static void onNewRaider(final Raider raider) {
        Session active = SESSION.get();
        if (active != null)
            active.raiders.computeIfAbsent(raider.getType().toShortString(), ignored -> new LongAdder()).increment();
    }

    public static void onInvalidated(final ServerLevel level, final Raid raid, final String reason) {
        Session active = SESSION.get();
        if (active != null) active.invalidated.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
        log("Raid #" + raid.idOrNegativeOne + " invalidated: " + reason + " in " + level.dimension().identifier());
    }

    public static void onCreated(final ServerLevel level, final Raid raid) {
        log("Raid #" + raid.idOrNegativeOne + " created at " + raid.getCenter().toShortString() + " in " + level.dimension().identifier());
    }

    public static void onOmenIncreased(final ServerLevel level, final Raid raid, final int omen) {
        log("Raid #" + raid.idOrNegativeOne + " raid omen increased to " + omen + " in " + level.dimension().identifier());
    }

    public static void onCenterMoved(final ServerLevel level, final Raid raid, final BlockPos center) {
        log("Raid #" + raid.idOrNegativeOne + " center moved to " + center.toShortString() + " in " + level.dimension().identifier());
    }

    private static void log(final String message) {
        if (CarpetLoggerProtocol.hasSubscribers("raid"))
            CarpetLoggerProtocol.log("raid", option -> List.of(Component.literal(message)));
    }

    private static final class Session {
        private final long startTick, startMillis;
        private final AtomicLong generated = new AtomicLong();
        private final Map<String, LongAdder> raiders = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> invalidated = new ConcurrentHashMap<>();

        private Session(final long startTick, final long startMillis) {
            this.startTick = startTick;
            this.startMillis = startMillis;
        }
    }
}
