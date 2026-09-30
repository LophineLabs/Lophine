package fun.bm.lophine.protocol;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.kyori.adventure.text.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.util.HopperCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@LeavesProtocol.Register(namespace = "carpet")
public class CarpetLoggerProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LoggerFactory.getLogger("CarpetLoggerProtocol");
    private static final Map<String, Map<String, String>> PLAYER_SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> configuredSubscriptions = null;

    public static void refreshConfiguredDefaults(boolean initial) {
        Map<String, String> defaults = parseConfiguredDefaults(GeneralCompatConfig.defaultLoggers);
        configuredSubscriptions = defaults;
        if (defaults == null || defaults.isEmpty()) {
            PLAYER_SUBSCRIPTIONS.clear();
            if (initial) return;
            for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
                clearHud(player);
            }
            return;
        }
        PLAYER_SUBSCRIPTIONS.replaceAll((playerName, ignored) -> defaults);
    }

    public static String serializeConfiguredDefaults(List<String> configuredLoggers) {
        List<String> serialized = new ArrayList<>();
        if (configuredLoggers != null) {
            for (String entry : configuredLoggers) {
                if (entry == null) {
                    continue;
                }
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    serialized.add(trimmed);
                }
            }
        }
        return serialized.isEmpty() ? "none" : String.join(",", serialized);
    }

    @ProtocolHandler.PlayerJoin
    public static void onPlayerJoin(ServerPlayer player) {
        if (configuredSubscriptions != null && !configuredSubscriptions.isEmpty()) {
            PLAYER_SUBSCRIPTIONS.putIfAbsent(player.getScoreboardName(), configuredSubscriptions);
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        clearHud(player);
    }

    @ProtocolHandler.Ticker(tickerId = "hud")
    public static void onHudTick() {
        if (PLAYER_SUBSCRIPTIONS.isEmpty()) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, String> subscriptions = PLAYER_SUBSCRIPTIONS.get(player.getScoreboardName());
            if (subscriptions == null || subscriptions.isEmpty()) {
                continue;
            }
            player.getBukkitEntity().taskScheduler.schedule((LivingEntity livingEntity) -> {
                if (livingEntity instanceof ServerPlayer scheduledPlayer) {
                    sendHud(server, scheduledPlayer, subscriptions);
                }
            }, null, 1L);
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public int tickerInterval(String tickerID) {
        return "hud".equals(tickerID) ? 20 : 1;
    }

    private static void sendHud(MinecraftServer server, ServerPlayer player, Map<String, String> subscriptions) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        subscriptions.forEach((loggerName, option) -> {
            switch (loggerName) {
                case "tps" -> lines.add(buildTpsLine(server));
                case "mobcaps" -> {
                    net.minecraft.network.chat.Component line = buildMobcapsLine(player, option);
                    if (line != null) {
                        lines.add(line);
                    }
                }
                case "counter" -> lines.addAll(buildCounterLines(player.level(), option));
                default -> {
                }
            }
        });

        MutableComponent footer = net.minecraft.network.chat.Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                footer.append(net.minecraft.network.chat.Component.literal("\n"));
            }
            footer.append(lines.get(i));
        }
        player.connection.send(new ClientboundTabListPacket(net.minecraft.network.chat.Component.empty(), footer));
    }

    private static void clearHud(ServerPlayer player) {
        player.connection.send(new ClientboundTabListPacket(net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty()));
    }

    private static net.minecraft.network.chat.Component buildTpsLine(MinecraftServer server) {
        ServerTickRateManager tickManager = server.tickRateManager();
        ca.spottedleaf.common.time.TickData.TickReportData tickData = TickRegionScheduler.getCurrentRegion().getData().getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        final double tps = tickData.tpsData().segmentAll().average();
        final double mspt = tickData.timePerTickData().segmentAll().average() / 1.0E6;

        ChatFormatting color = heatmapColor(mspt, tickManager.millisecondsPerTick());
        return net.minecraft.network.chat.Component.empty()
                .append(net.minecraft.network.chat.Component.literal("TPS: ").withStyle(ChatFormatting.GRAY))
                .append(net.minecraft.network.chat.Component.literal(String.format(Locale.US, "%.1f", tps)).withStyle(color))
                .append(net.minecraft.network.chat.Component.literal("  MSPT: ").withStyle(ChatFormatting.GRAY))
                .append(net.minecraft.network.chat.Component.literal(String.format(Locale.US, "%.1f", mspt)).withStyle(color));
    }

    private static net.minecraft.network.chat.Component buildMobcapsLine(ServerPlayer player, String option) {
        final ServerLevel level = resolveMobcapsLevel(player, option);
        if (level == null) {
            return null;
        }

        final RegionizedWorldData data = level.getCurrentWorldData();
        if (data == null) {
            return null;
        }

        final NaturalSpawner.SpawnState spawnState = data.lastSpawnState;
        if (spawnState == null) {
            return net.minecraft.network.chat.Component.literal("Mobcaps: unavailable").withStyle(ChatFormatting.DARK_GRAY);
        }

        int chunks = spawnState.getSpawnableChunkCount();
        var counts = spawnState.getMobCategoryCounts();
        MutableComponent line = net.minecraft.network.chat.Component.literal("Mobcaps").withStyle(ChatFormatting.GRAY);
        for (MobCategory category : MobCategory.values()) {
            if (category == MobCategory.MISC) {
                continue;
            }
            int current = counts.getOrDefault(category, 0);
            int limit = NaturalSpawner.globalLimitForCategory(level, category, chunks);
            line.append(net.minecraft.network.chat.Component.literal("  " + shortName(category) + " ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(net.minecraft.network.chat.Component.literal(current + "/" + limit).withStyle(categoryColor(current, limit)));
        }
        return line;
    }

    private static List<net.minecraft.network.chat.Component> buildCounterLines(ServerLevel level, String option) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        String colors = option == null || option.isBlank() ? "white" : option;
        for (String rawColor : colors.split(",")) {
            String colorName = rawColor.trim();
            if (colorName.isEmpty()) {
                continue;
            }
            DyeColor color = DyeColor.byName(colorName, null);
            if (color == null) {
                continue;
            }
            HopperCounter counter = HopperCounter.getCounter(color);
            if (counter == null) {
                continue;
            }
            for (Component component : counter.format(level, false)) {
                lines.add(PaperAdventure.asVanilla(component));
            }
        }
        return lines;
    }

    private static ServerLevel resolveMobcapsLevel(ServerPlayer player, String option) {
        if (option == null || option.isBlank() || option.equalsIgnoreCase("dynamic")) {
            return player.level();
        }
        return switch (option.toLowerCase(Locale.ROOT)) {
            case "overworld" -> player.level().dimension() == Level.OVERWORLD ? player.level() : null;
            case "nether" -> player.level().dimension() == Level.NETHER ? player.level() : null;
            case "end" -> player.level().dimension() == Level.END ? player.level() : null;
            default -> player.level();
        };
    }

    private static ChatFormatting heatmapColor(double actual, double reference) {
        if (actual > reference) {
            return ChatFormatting.LIGHT_PURPLE;
        }
        if (actual > 0.8D * reference) {
            return ChatFormatting.RED;
        }
        if (actual > 0.5D * reference) {
            return ChatFormatting.YELLOW;
        }
        if (actual >= 0.0D) {
            return ChatFormatting.DARK_GREEN;
        }
        return ChatFormatting.GRAY;
    }

    private static ChatFormatting categoryColor(int current, int limit) {
        if (limit <= 0) {
            return ChatFormatting.DARK_GRAY;
        }
        double ratio = current / (double) limit;
        if (ratio >= 1.0D) {
            return ChatFormatting.RED;
        }
        if (ratio >= 0.8D) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.GREEN;
    }

    private static String shortName(MobCategory category) {
        return switch (category) {
            case MONSTER -> "M";
            case CREATURE -> "C";
            case AMBIENT -> "A";
            case AXOLOTLS -> "Ax";
            case UNDERGROUND_WATER_CREATURE -> "UWC";
            case WATER_CREATURE -> "WC";
            case WATER_AMBIENT -> "WA";
            case MISC -> "X";
        };
    }

    private static Map<String, String> parseConfiguredDefaults(List<String> configuredLoggers) {
        LinkedHashMap<String, String> subscriptions = new LinkedHashMap<>();
        if (configuredLoggers == null) {
            return null;
        }
        for (String entry : configuredLoggers) {
            if (entry == null) {
                continue;
            }
            for (String chunk : entry.split(",")) {
                String token = chunk.trim();
                if (token.isEmpty() || token.equalsIgnoreCase("none")) {
                    continue;
                }
                String[] parts = token.split("\\s+", 2);
                String loggerName = parts[0].toLowerCase(Locale.ROOT);
                if (!isSupported(loggerName)) {
                    LOGGER.debug("Ignoring unsupported Carpet default logger '{}'", loggerName);
                    continue;
                }
                String option = parts.length == 1 ? defaultOption(loggerName) : parts[1].trim();
                if (option.isEmpty()) {
                    option = defaultOption(loggerName);
                }
                subscriptions.put(loggerName, option);
            }
        }
        return subscriptions.isEmpty() ? null : Map.copyOf(subscriptions);
    }

    private static boolean isSupported(String loggerName) {
        return Arrays.asList("tps", "mobcaps", "counter").contains(loggerName);
    }

    private static @NotNull String defaultOption(String loggerName) {
        return switch (loggerName) {
            case "mobcaps" -> "dynamic";
            case "counter" -> "white";
            default -> "";
        };
    }
}
