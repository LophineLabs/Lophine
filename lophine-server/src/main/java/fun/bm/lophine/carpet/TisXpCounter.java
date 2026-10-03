/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.*;

public final class TisXpCounter {
    private static final Map<DyeColor, Counter> COUNTERS = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor color : DyeColor.values()) COUNTERS.put(color, new Counter());
    }

    private TisXpCounter() {
    }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("xcounter", "white", Arrays.stream(DyeColor.values()).map(DyeColor::getName).toList(), false);
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        registerLogger();
        dispatcher.register(Commands.literal("xcounter").requires(source -> GeneralCompatConfig.hopperXpCounters)
                .executes(context -> reportAll(context.getSource()))
                .then(Commands.literal("reset").executes(context -> {
                    resetAtShutdown();
                    TisRaycastCommand.feedback(context.getSource(), "All XP counters reset.");
                    return 1;
                }))
                .then(Commands.argument("color", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(DyeColor.values()).map(DyeColor::getName), builder))
                        .executes(context -> report(context.getSource(), StringArgumentType.getString(context, "color"), false))
                        .then(Commands.literal("realtime").executes(context -> report(context.getSource(), StringArgumentType.getString(context, "color"), true)))
                        .then(Commands.literal("reset").executes(context -> {
                            DyeColor color = color(StringArgumentType.getString(context, "color"));
                            if (color == null)
                                return unknown(context.getSource(), StringArgumentType.getString(context, "color"));
                            COUNTERS.get(color).reset();
                            TisRaycastCommand.feedback(context.getSource(), color.getName() + " XP counter reset.");
                            return 1;
                        }))));
    }

    private static DyeColor hopperColor(final HopperBlockEntity hopper) {
        if (!GeneralCompatConfig.hopperXpCounters || !(hopper.getLevel() instanceof ServerLevel level)) return null;
        BlockPos wool = hopper.getBlockPos().relative(hopper.getBlockState().getValue(HopperBlock.FACING));
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, wool)) return null;
        return org.leavesmc.leaves.util.WoolUtils.getWoolColorAtPosition(level, wool);
    }

    public static boolean isCounterHopper(final HopperBlockEntity hopper) {
        return hopperColor(hopper) != null;
    }

    public static void tickHopper(final HopperBlockEntity hopper) {
        DyeColor color = hopperColor(hopper);
        if (color == null || !(hopper.getLevel() instanceof ServerLevel level)) return;
        var mouth = hopper.getSuckAabb().move(hopper.getLevelX() - 0.5, hopper.getLevelY() - 0.5, hopper.getLevelZ() - 0.5);
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, mouth,
                entity -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity) && entity.isAlive())) {
            COUNTERS.get(color).record(orb.getValue(), orb.count, gameTime(level.getServer()), System.currentTimeMillis());
            orb.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP);
        }
    }

    public static List<Component> hud(final String option, final MinecraftServer server) {
        List<Component> lines = new ArrayList<>();
        for (String name : option.split(",")) {
            DyeColor color = color(name);
            if (color != null) lines.add(Component.literal(COUNTERS.get(color).brief(color, gameTime(server))));
        }
        return lines;
    }

    private static long gameTime(final MinecraftServer server) {
        return CarpetServerClock.gameTime();
    }

    private static int reportAll(final CommandSourceStack source) {
        int printed = 0;
        for (DyeColor color : DyeColor.values()) {
            List<String> lines = COUNTERS.get(color).report(color, gameTime(source.getServer()), System.currentTimeMillis(), false);
            if (lines.size() > 1) {
                ++printed;
                lines.forEach(line -> TisRaycastCommand.feedback(source, line));
            }
        }
        if (printed == 0) TisRaycastCommand.feedback(source, "No XP has been counted yet.");
        return printed;
    }

    private static int report(final CommandSourceStack source, final String name, final boolean realtime) {
        DyeColor color = color(name);
        if (color == null) return unknown(source, name);
        COUNTERS.get(color).report(color, gameTime(source.getServer()), System.currentTimeMillis(), realtime)
                .forEach(line -> TisRaycastCommand.feedback(source, line));
        return 1;
    }

    private static int unknown(final CommandSourceStack source, final String name) {
        TisRaycastCommand.feedback(source, "Unknown wool color: " + name);
        return 0;
    }

    private static DyeColor color(final String name) {
        try {
            return DyeColor.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static void resetAtShutdown() {
        COUNTERS.values().forEach(Counter::reset);
    }

    private static final class Counter {
        private final Map<Integer, Long> xpByValue = new TreeMap<>(java.util.Comparator.reverseOrder());
        private long startTick, startMillis;
        private boolean running;

        private synchronized void record(final int value, final int count, final long tick, final long millis) {
            if (!this.running) {
                this.running = true;
                this.startTick = tick;
                this.startMillis = millis;
                this.xpByValue.clear();
            }
            this.xpByValue.merge(value, (long) value * count, Long::sum);
        }

        private synchronized void reset() {
            this.running = false;
            this.xpByValue.clear();
        }

        private synchronized List<String> report(final DyeColor color, final long nowTick, final long nowMillis, final boolean realtime) {
            if (!this.running) return List.of(color.getName() + " XP counter: not started.");
            long ticks = Math.max(1L, realtime ? (nowMillis - this.startMillis) / 50L : nowTick - this.startTick);
            long total = this.xpByValue.values().stream().mapToLong(Long::longValue).sum();
            List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT, "%s XP: %d, %.2f/h over %.2f minutes%s", color.getName(), total, total * 72000.0 / ticks,
                    ticks / 1200.0, realtime ? " (real time)" : ""));
            this.xpByValue.forEach((value, xp) -> lines.add(String.format(Locale.ROOT, " - orb value %d (size %d): %d XP, %.2f/h",
                    value, orbSize(value), xp, xp * 72000.0 / ticks)));
            return lines;
        }

        private synchronized String brief(final DyeColor color, final long nowTick) {
            if (!this.running) return color.getName() + ": N/A (X)";
            long ticks = Math.max(1L, nowTick - this.startTick), total = this.xpByValue.values().stream().mapToLong(Long::longValue).sum();
            return String.format(Locale.ROOT, "%s: %d, %.1f/h, %.1f min (X)", color.getName(), total, total * 72000.0 / ticks, ticks / 1200.0);
        }

        private static int orbSize(final int value) {
            int[] thresholds = {0, 3, 7, 17, 37, 73, 149, 307, 617, 1237, 2477};
            for (int size = thresholds.length - 1; size >= 0; --size) if (value >= thresholds[size]) return size;
            return 0;
        }
    }
}
