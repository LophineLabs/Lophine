// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

public final class TisSamplingLoggers {
    private static final IdWindow ENTITY_IDS = new IdWindow();
    private static final Map<ServerLevel, LightWindow> LIGHT_WINDOWS = new ConcurrentHashMap<>();

    private TisSamplingLoggers() { }

    public static void registerLoggers() {
        CarpetLoggerProtocol.registerLogger("entityIdCounter", "hud", List.of("hud", "increment", "all"), false);
        CarpetLoggerProtocol.registerLogger("lightQueue", "dynamic", List.of("dynamic", "overworld", "the_nether", "the_end"), false);
    }

    // Run once per Folia global tick; engine references and counters are safe across lighting workers.
    public static void globalTick(MinecraftServer server) {
        ENTITY_IDS.add(ServerLevel.carpetEntityIdCounter());
        boolean subscribed = CarpetLoggerProtocol.hasSubscribers("lightQueue");
        for (ServerLevel level : server.getAllLevels()) {
            var queue = level.getChunkSource().getLightEngine().starlight$getLightEngine().getServerLightQueue();
            if (queue == null) continue;
            long[] sample = queue.carpetDrainLightStatistics();
            if (subscribed) LIGHT_WINDOWS.computeIfAbsent(level, ignored -> new LightWindow()).add(sample);
            else LIGHT_WINDOWS.remove(level);
        }
    }

    public static void entityAllocated(int id, EntityType<?> type, String uuid) {
        if (!CarpetLoggerProtocol.hasSubscribers("entityIdCounter")) return;
        String thread = Thread.currentThread().getName();
        String trace = Arrays.stream(Thread.currentThread().getStackTrace()).skip(2).map(StackTraceElement::toString)
            .collect(Collectors.joining("\n"));
        Component symbol = Component.literal(" [stack]").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(trace))));
        Component entity = type.getDescription().copy().withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(uuid)))
            .withClickEvent(new ClickEvent.SuggestCommand("/tp " + uuid)));
        Component line = Component.literal("[EID] ").withStyle(ChatFormatting.BLUE).append(entity)
            .append(Component.literal(" #" + id).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" on " + thread).withStyle(ChatFormatting.GRAY)).append(symbol);
        CarpetLoggerProtocol.log("entityIdCounter", option -> contains(option, "increment") ? List.of(line) : List.of());
    }

    public static List<Component> entityHud(String option) {
        if (!contains(option, "hud")) return List.of();
        int value = ServerLevel.carpetEntityIdCounter();
        long distanceToZero = value > 0 ? (1L << 32) - value : -(long) value;
        double percent = 100.0 * ((1L << 32) - distanceToZero) / (1L << 32);
        double speed = ENTITY_IDS.rate() * 20.0;
        double hours = (1L << 32) / Math.max(0.123, speed * 3600.0);
        ChatFormatting percentColor = percent > 99.99 ? ChatFormatting.RED : percent > 99.9 ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
        ChatFormatting speedColor = hours < 24 ? ChatFormatting.GOLD : hours < 24 * 30 ? ChatFormatting.WHITE
            : speed >= 1.0e-6 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY;
        return List.of(Component.literal("EID ").withStyle(ChatFormatting.BLUE)
            .append(Component.literal(Integer.toString(value)).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, " %.2f%% ", percent)).withStyle(percentColor))
            .append(Component.literal(String.format(Locale.ROOT, speed < 10 ? "%.1f/s" : "%.0f/s", speed)).withStyle(speedColor)));
    }

    // Only the player's dimension is read here, on that player's owner; other-world data is immutable snapshots.
    public static List<Component> lightHud(String option, ServerPlayer player) {
        ServerLevel world = player.level();
        String identifier = option != null && option.contains(":") ? option : "minecraft:" + option;
        for (ServerLevel candidate : LIGHT_WINDOWS.keySet()) {
            if (candidate.dimension().identifier().toString().equals(identifier)) { world = candidate; break; }
        }
        LightWindow recorder = LIGHT_WINDOWS.get(world);
        LightSummary summary = recorder == null ? new LightSummary(0, 0, 0) : recorder.summary();
        double increase = summary.enqueued - summary.executed;
        return List.of(Component.literal("LQ(" + world.dimension().identifier().getPath() + ") ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format(Locale.ROOT, "%+.1f/gt", increase))
                    .withStyle(increase >= 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GREEN))
                .append(Component.literal(String.format(Locale.ROOT, "  S: %d  T: %.1f gt", summary.queueSize,
                    summary.executed > 0 ? summary.queueSize / summary.executed : 0.0)).withStyle(ChatFormatting.GRAY)),
            Component.literal(String.format(Locale.ROOT, "Light I/O: %.1f/%.1f", summary.enqueued, summary.executed))
                .withStyle(ChatFormatting.GRAY));
    }

    private static boolean contains(String option, String selected) {
        return "all".equals(option) || option != null && Arrays.asList(option.split(",")).contains(selected);
    }

    public static void reset() {
        ENTITY_IDS.clear();
        LIGHT_WINDOWS.clear();
    }

    private static final class IdWindow {
        private final ArrayDeque<Long> values = new ArrayDeque<>();
        private Integer previous;
        private long sum;

        synchronized void add(int value) {
            long delta = previous == null ? 0L : Integer.toUnsignedLong(value - previous);
            previous = value;
            values.addLast(delta);
            sum += delta;
            int duration = Math.max(1, GeneralCompatConfig.entityIdCounterLoggerSamplingDuration);
            while (values.size() > duration) sum -= values.removeFirst();
        }

        synchronized double rate() { return values.isEmpty() ? 0.0 : (double) sum / values.size(); }
        synchronized void clear() { values.clear(); previous = null; sum = 0L; }
    }

    private record LightSummary(double enqueued, double executed, long queueSize) { }

    private static final class LightWindow {
        private final ArrayDeque<long[]> values = new ArrayDeque<>();
        private long enqueued;
        private long executed;

        synchronized void add(long[] sample) {
            values.addLast(sample);
            enqueued += sample[0];
            executed += sample[1];
            int duration = Math.max(1, GeneralCompatConfig.lightQueueLoggerSamplingDuration);
            while (values.size() > duration) {
                long[] oldest = values.removeFirst();
                enqueued -= oldest[0];
                executed -= oldest[1];
            }
        }

        synchronized LightSummary summary() {
            return values.isEmpty() ? new LightSummary(0, 0, 0) :
                new LightSummary((double) enqueued / values.size(), (double) executed / values.size(), values.peekLast()[2]);
        }
    }
}
