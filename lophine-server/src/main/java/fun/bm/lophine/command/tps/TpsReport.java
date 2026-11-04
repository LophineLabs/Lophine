package fun.bm.lophine.command.tps;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkFullTask;
import fun.bm.lophine.utils.ServerI18nUtil;
import fun.bm.lophine.utils.markdown.MarkdownArgs;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.*;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class TpsReport {
    private static final int ENTITY_HOVER_LIMIT = 10;
    private static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));
    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));
    private static final ThreadLocal<DecimalFormat> NO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0"));

    public static boolean reportRegion(CommandSender sender) {
        if (!(sender instanceof Entity)) {
            sender.sendMessage(localized("lophine.command.tps.region.console"));
            return true;
        }

        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            sender.sendMessage(localized("lophine.command.tps.region.missing"));
            return true;
        }

        long now = System.nanoTime();
        TickData.TickReportData report15s = region.getData().getRegionSchedulingHandle().getTickReport15s(now);
        TickData.TickReportData report1m = region.getData().getRegionSchedulingHandle().getTickReport1m(now);
        TickRegions.RegionStats stats = region.getData().getRegionStats();
        Location location = locationOf(region);
        if (location == null) {
            sender.sendMessage(localized("lophine.command.tps.region.missing"));
            return true;
        }

        RegionLists lists = snapshotLists(region);
        sender.sendMessage(localized("lophine.command.tps.region.report", args()
                .set("world", location.world())
                .set("x", location.x())
                .set("z", location.z())
                .set("key", location.key())
                .set("util15s", oneDecimal(report15s.utilisation() * 100.0), report15s.utilisation())
                .set("mspt15s", twoDecimal(mspt(report15s)), mspt(report15s))
                .set("tps15s", twoDecimal(tps(report15s)), tps(report15s))
                .set("util1m", oneDecimal(report1m.utilisation() * 100.0), report1m.utilisation())
                .set("mspt1m", twoDecimal(mspt(report1m)), mspt(report1m))
                .set("tps1m", twoDecimal(tps(report1m)), tps(report1m))
                .set("chunks", noDecimal(stats.getChunkCount()))
                .set("players", hoverable(noDecimal(stats.getPlayerCount()), lists.playerHover()))
                .set("entities", hoverable(noDecimal(stats.getEntityCount()), lists.entityHover()))
        ));
        return true;
    }

    public static boolean reportServer(CommandSender sender, int regionCount) {
        List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
        for (World bukkitWorld : Bukkit.getWorlds()) {
            ((CraftWorld) bukkitWorld).getHandle().regioniser.computeForAllRegions(regions::add);
        }

        long now = System.nanoTime();
        TickData.TickReportData globalTickReport = RegionizedServer.getGlobalTickData().getTickReport15s(now);
        int maxThreadCount = TickRegions.getScheduler().getTotalThreadCount();
        MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long usedMemory = heapUsage.getUsed() / (1024 * 1024);
        long maxMemory = heapUsage.getMax() / (1024 * 1024);
        double memPercent = maxMemory == 0 ? 0.0 : (double) usedMemory / maxMemory * 100.0;

        DoubleArrayList tpsByRegion = new DoubleArrayList();
        List<TickData.TickReportData> reportsByRegion = new ArrayList<>(regions.size());
        double totalUtil = 0.0;
        long totalChunks = 0;
        long totalEntities = 0;
        for (ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : regions) {
            TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport15s(now);
            tpsByRegion.add(report == null ? 20.0 : tps(report));
            reportsByRegion.add(report);
            totalUtil += report == null ? 0.0 : report.utilisation();
            TickRegions.RegionStats stats = region.getData().getRegionStats();
            totalChunks += stats.getChunkCount();
            totalEntities += stats.getEntityCount();
        }
        totalUtil += globalTickReport.utilisation();
        double utilFraction = totalUtil / (double) maxThreadCount;

        tpsByRegion.sort(null);
        double minTps;
        double medianTps;
        double maxTps;
        if (tpsByRegion.isEmpty()) {
            minTps = medianTps = maxTps = 20.0;
        } else {
            minTps = tpsByRegion.getDouble(0);
            maxTps = tpsByRegion.getDouble(tpsByRegion.size() - 1);
            int middle = tpsByRegion.size() >> 1;
            if ((tpsByRegion.size() & 1) == 0) {
                medianTps = (tpsByRegion.getDouble(middle - 1) + tpsByRegion.getDouble(middle)) / 2.0;
            } else {
                medianTps = tpsByRegion.getDouble(middle);
            }
        }

        List<ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>> hottest =
                new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            hottest.add(new ObjectObjectImmutablePair<>(regions.get(i), reportsByRegion.get(i)));
        }
        hottest.sort((left, right) -> Double.compare(
                right.right() == null ? 0.0 : right.right().utilisation(),
                left.right() == null ? 0.0 : left.right().utilisation()
        ));

        Component hottestRegions = buildHottestRegions(sender, hottest, regionCount);
        sender.sendMessage(localized("lophine.command.tps.server.report", args()
                .set("uptime", formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()), utilFraction)
                .set("players", Bukkit.getOnlinePlayers().size())
                .set("regions", regions.size())
                .set("chunks", noDecimal(totalChunks))
                .set("entities", noDecimal(totalEntities))
                .set("util", oneDecimal(totalUtil * 100.0), utilFraction)
                .set("maxUtil", oneDecimal(maxThreadCount * 100.0))
                .set("load", twoDecimal(ChunkFullTask.loadRate(now)))
                .set("gen", twoDecimal(ChunkFullTask.genRate(now)))
                .set("used", noDecimal(usedMemory) + " MB", memPercent)
                .set("max", noDecimal(maxMemory) + " MB")
                .set("memPercent", oneDecimal(memPercent) + "%", memPercent)
                .set("minTps", twoDecimal(minTps), minTps)
                .set("medianTps", twoDecimal(medianTps), medianTps)
                .set("maxTps", twoDecimal(maxTps), maxTps)
                .set("count", regionCount)
                .set("hottest", hottestRegions)
        ));
        return true;
    }

    private static Component buildHottestRegions(
            CommandSender sender,
            List<ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData>> hottest,
            int regionCount
    ) {
        Component result = sender instanceof Player
                ? localized("lophine.command.tps.server.teleport_hint")
                : Component.empty();
        int included = 0;
        int limit = Math.min(regionCount, hottest.size());
        for (int i = 0; i < limit; i++) {
            ObjectObjectImmutablePair<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, TickData.TickReportData> pair = hottest.get(i);
            TickData.TickReportData report = pair.right();
            if (report == null) {
                continue;
            }
            Location location = locationOf(pair.left());
            if (location == null) {
                continue;
            }
            included++;
            boolean last = i + 1 == limit;
            TickRegions.RegionStats stats = pair.left().getData().getRegionStats();
            RegionLists lists = snapshotLists(pair.left());
            result = result.append(localized("lophine.command.tps.server.region", args()
                    .set("world", location.world())
                    .set("x", location.x())
                    .set("z", location.z())
                    .set("key", location.key())
                    .set("util", oneDecimal(report.utilisation() * 100.0), report.utilisation())
                    .set("mspt", twoDecimal(mspt(report)), mspt(report))
                    .set("tps", twoDecimal(tps(report)), tps(report))
                    .set("chunks", noDecimal(stats.getChunkCount()))
                    .set("players", hoverable(noDecimal(stats.getPlayerCount()), lists.playerHover()))
                    .set("entities", hoverable(noDecimal(stats.getEntityCount()), lists.entityHover()))
                    .set("newline", last ? "" : "\n")
            ));
        }
        return included == 0 && !(sender instanceof Player) ? Component.empty() : result;
    }

    private static RegionLists snapshotLists(
            ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
    ) {
        if (TickRegionScheduler.getCurrentRegion() == region) {
            return snapshotListsNow();
        }
        net.minecraft.world.level.ChunkPos center = region.getCenterChunk();
        if (center == null) {
            return RegionLists.EMPTY;
        }
        CompletableFuture<RegionLists> future = new CompletableFuture<>();
        RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(region.regioniser.world, center.x(), center.z(), () -> {
            try {
                future.complete(snapshotListsNow());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(250, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return RegionLists.EMPTY;
        }
    }

    private static RegionLists snapshotListsNow() {
        RegionizedWorldData worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return RegionLists.EMPTY;
        }
        List<String> players = new ArrayList<>();
        for (ServerPlayer player : worldData.getLocalPlayers()) {
            players.add(player.getGameProfile().name() + "[" + player.getUUID() + "]");
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Component> names = new HashMap<>();
        for (net.minecraft.world.entity.Entity entity : worldData.getLocalEntities()) {
            if (entity instanceof ServerPlayer) {
                continue;
            }
            String key = entity.getType().toShortString();
            counts.merge(key, 1, Integer::sum);
            names.putIfAbsent(key, PaperAdventure.asAdventure(entity.getType().getDescription()));
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        int extraTypes = Math.max(0, ranked.size() - ENTITY_HOVER_LIMIT);
        if (ranked.size() > ENTITY_HOVER_LIMIT) {
            ranked = ranked.subList(0, ENTITY_HOVER_LIMIT);
        }
        List<Component> entities = new ArrayList<>(ranked.size());
        for (Map.Entry<String, Integer> entry : ranked) {
            entities.add(names.get(entry.getKey()).append(Component.text(": " + noDecimal(entry.getValue()))));
        }
        return new RegionLists(players, entities, extraTypes);
    }

    private static Component hoverable(String display, @Nullable Component hover) {
        Component text = Component.text(display, net.kyori.adventure.text.format.TextColor.color(180, 220, 255));
        return hover == null ? text : text.hoverEvent(HoverEvent.showText(hover));
    }

    private static @Nullable Location locationOf(
            ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
    ) {
        net.minecraft.world.level.ChunkPos chunkCenter = region.getCenterChunk();
        if (chunkCenter == null) {
            return null;
        }
        org.bukkit.World world = region.regioniser.world.getWorld();
        return new Location(
                world.getName(),
                world.getKey().toString(),
                (chunkCenter.x() << 4) | 7,
                (chunkCenter.z() << 4) | 7
        );
    }

    private static double tps(TickData.TickReportData report) {
        return report.tpsData().segmentAll().average();
    }

    private static double mspt(TickData.TickReportData report) {
        return report.timePerTickData().segmentAll().average() / 1.0E6;
    }

    private static String formatUptime(long uptimeMillis) {
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMillis);
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis) % 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(seconds).append("s");
        return builder.toString();
    }

    private static MarkdownArgs args() {
        return MarkdownArgs.create();
    }

    private static Component localized(String key) {
        return ServerI18nUtil.getLocalizedComponent(key);
    }

    private static Component localized(String key, MarkdownArgs args) {
        return ServerI18nUtil.getLocalizedComponent(key, args);
    }

    private static String twoDecimal(double value) {
        return TWO_DECIMAL_PLACES.get().format(value);
    }

    private static String oneDecimal(double value) {
        return ONE_DECIMAL_PLACES.get().format(value);
    }

    private static String noDecimal(long value) {
        return NO_DECIMAL_PLACES.get().format(value);
    }

    private record Location(@NotNull String world, @NotNull String key, int x, int z) {
    }

    private record RegionLists(List<String> players, List<Component> entities, int extraEntities) {
        private static final RegionLists EMPTY = new RegionLists(List.of(), List.of(), 0);

        @Nullable
        private Component playerHover() {
            if (players.isEmpty()) {
                return null;
            }
            Component result = Component.empty();
            for (int i = 0; i < players.size(); i++) {
                if (i > 0) {
                    result = result.append(Component.newline());
                }
                result = result.append(Component.text(players.get(i)));
            }
            return result;
        }

        @Nullable
        private Component entityHover() {
            if (entities.isEmpty()) {
                return null;
            }
            Component result = Component.empty();
            for (int i = 0; i < entities.size(); i++) {
                if (i > 0) {
                    result = result.append(Component.newline());
                }
                result = result.append(entities.get(i));
            }
            if (extraEntities > 0) {
                result = result.append(Component.newline()).append(Component.text("+" + extraEntities + "..."));
            }
            return result;
        }
    }
}
