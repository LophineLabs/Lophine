package fun.bm.lophine.carpet;

import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Independent native timer sampling over complete global tick windows. */
public final class CarpetProfileService {
    private static volatile Request pending;
    private static volatile Session current;

    private CarpetProfileService() {}

    private record Request(CommandSourceStack source, int ticks, boolean entities) {}
    private static final class Session {
        final Request request;
        final CarpetProfileObserver.Session sampling;
        int remaining;
        Session(Request request, long now) {
            this.request = request;
            this.remaining = request.ticks();
            this.sampling = CarpetProfileObserver.begin(now);
        }
    }

    public static int request(CommandSourceStack source, int ticks, boolean entities) {
        if (ticks < 20 || ticks > 24000) throw new IllegalArgumentException("Expected 20 to 24000 ticks");
        RegionizedServer.getInstance().addTask(() -> pending = new Request(source, ticks, entities));
        return 1;
    }

    /** Called at the actual global tick head before its first measured phase. */
    public static void startGlobalTick() {
        if (pending != null) {
            if (current != null) CarpetProfileObserver.finish(current.sampling, System.nanoTime());
            Request request = pending;
            pending = null;
            current = new Session(request, System.nanoTime());
        }
    }

    /** Called at actual global tick tail, after closing the global Full Tick scope. */
    public static void endGlobalTick() {
        Session session = current;
        if (session == null || --session.remaining > 0) return;
        current = null;
        finish(session, CarpetProfileObserver.finish(session.sampling, System.nanoTime()));
    }

    public static void reset() { pending = null; current = null; CarpetProfileObserver.reset(); }

    private static void finish(Session session, CarpetProfileObserver.Result result) {
        Map<String, Long> sections = new HashMap<>(), selfSections = new HashMap<>();
        Map<String, Long> counts = new HashMap<>(), entityTimes = new HashMap<>(), counters = new HashMap<>();
        long regional = 0L, between = 0L, global = 0L;
        int regions = 0;
        for (var region : result.regions()) {
            boolean isGlobal = region.key().regionId() == -1L;
            if (!isGlobal) regions++;
            for (var timer : region.timers().entrySet()) {
                String name = timer.getKey();
                var timing = timer.getValue();
                if (name.equals("Full Tick")) {
                    if (isGlobal) global += timing.nanos(); else regional += timing.nanos();
                }
                if (!isGlobal && name.equals("In Between Tick")) between += timing.nanos();
                String section = region.key().dimension() + " - " + name;
                sections.merge(section, timing.nanos(), Long::sum);
                selfSections.merge(section, timing.selfNanos(), Long::sum);
                String type = entityType(name);
                if (type != null) {
                    String key = type + " in " + region.key().dimension();
                    counts.merge(key, timing.calls(), Long::sum);
                    entityTimes.merge(key, timing.nanos(), Long::sum);
                }
            }
            region.counters().forEach((name, count) -> counters.merge(region.key().dimension() + " - " + name, count, Long::sum));
        }
        double divider = 1.0 / session.request.ticks();
        send(session.request.source(), Component.literal(String.format(Locale.ROOT,
            "Average tick work: regions %.3fms, global %.3fms, between ticks %.3fms/global tick (%d ticks, %d region records)",
            regional * divider / 1.0E6, global * divider / 1.0E6, between * divider / 1.0E6, session.request.ticks(), regions)));
        send(session.request.source(), Component.literal(String.format(Locale.ROOT,
            "Wall clock: %.3fms/global tick; regional work runs in parallel. Window endpoints are clipped; section times are inclusive.",
            (result.endNanos() - result.startNanos()) * divider / 1.0E6)));
        if (session.request.entities()) {
            send(session.request.source(), Component.literal("Top 10 counts:"));
            showTop(session, counts, divider, "");
            send(session.request.source(), Component.literal("Top 10 CPU hogs:"));
            showTop(session, entityTimes, divider / 1.0E6, "ms");
        } else {
            for (var entry : sections.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (entityType(entry.getKey().substring(entry.getKey().indexOf(" - ") + 3)) != null) continue;
                double ms = entry.getValue() * divider / 1.0E6;
                if (ms > 0.01) send(session.request.source(), Component.literal(String.format(Locale.ROOT,
                    "%s: %.3fms (self %.3fms)", entry.getKey(), ms, selfSections.get(entry.getKey()) * divider / 1.0E6)));
            }
            for (var entry : counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                send(session.request.source(), Component.literal(String.format(Locale.ROOT,
                    "%s: %.3f/global tick", entry.getKey(), entry.getValue() * divider)));
            }
        }
    }

    private static String entityType(String name) {
        for (String prefix : List.of("Entity Tick: ", "Inactive Entity Tick: ", "Passenger Entity Tick: ", "Passenger Inactive Entity Tick: ", "Block Entity Tick: ")) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return null;
    }

    private static void showTop(Session session, Map<String, Long> values, double divider, String unit) {
        for (var entry : values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())).limit(10).toList()) {
            send(session.request.source(), Component.literal(String.format(Locale.ROOT, " - %s: %.3f%s", entry.getKey(), entry.getValue() * divider, unit)));
        }
    }

    private static void send(CommandSourceStack source, Component message) {
        if (source.getEntity() instanceof ServerPlayer player) {
            player.getBukkitEntity().taskScheduler.schedule(entity -> source.sendSuccess(() -> message, false), null, 1L);
        } else source.sendSuccess(() -> message, false);
    }
}
