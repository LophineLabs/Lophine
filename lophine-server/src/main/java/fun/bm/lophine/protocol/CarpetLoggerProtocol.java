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
    private static final Set<String> SEEN_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> ONLINE_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, LoggerOptions> LOGGERS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> configuredSubscriptions = null;

    static {
        registerLogger("tps", "", List.of(), false);
        registerLogger("packets", "", List.of(), false);
        registerLogger("tnt", "brief", List.of("brief", "full"), true);
        registerLogger("projectiles", "brief", List.of("brief", "full", "visualize"), false);
        registerLogger("fallingBlocks", "brief", List.of("brief", "full"), false);
        fun.bm.lophine.carpet.TisRaidCommand.registerLogger();
        fun.bm.lophine.carpet.TisXpCounter.registerLogger();
        fun.bm.lophine.carpet.TisSamplingLoggers.registerLoggers();
        fun.bm.lophine.carpet.TisMovementLogger.registerLogger();
        fun.bm.lophine.carpet.TisLifetimeTracker.registerLogger();
        fun.bm.lophine.carpet.TisMicroTiming.registerLogger();
        fun.bm.lophine.carpet.CarpetPathfindingLogger.registerLogger();
        fun.bm.lophine.carpet.OrgHiddenPathProtocol.registerLogger();
        fun.bm.lophine.carpet.CarpetExplosionLogger.registerLogger();
        registerLogger("mobcaps", "dynamic", List.of("dynamic", "overworld", "nether", "end"), false);
        registerLogger("counter", "white", Arrays.stream(DyeColor.values()).map(DyeColor::getName).toList(), false);
    }

    public record LoggerOptions(String defaultOption, List<String> options, boolean strict) {
        public LoggerOptions {
            options = List.copyOf(options);
        }
    }

    public static void registerLogger(String name, String defaultOption, List<String> options, boolean strict) {
        LOGGERS.put(name, new LoggerOptions(defaultOption, options, strict));
    }

    public static Set<String> loggerNames() {
        return Collections.unmodifiableSet(LOGGERS.keySet());
    }

    public static LoggerOptions loggerOptions(String name) {
        if ("lifetime".equals(name)) return new LoggerOptions("", fun.bm.lophine.carpet.TisLifetimeTracker.loggerOptions(), false);
        LoggerOptions options = LOGGERS.get(name);
        if (options == null) throw new IllegalArgumentException("Unknown logger: " + name);
        return options;
    }

    public static Map<String, String> subscriptions(String playerName) {
        return PLAYER_SUBSCRIPTIONS.getOrDefault(playerName, Map.of());
    }

    public static boolean hasSubscribers(String name) {
        for (String player : ONLINE_PLAYERS) {
            if (subscriptions(player).containsKey(name)) return true;
        }
        return false;
    }

    public static void log(String name, java.util.function.Function<String, List<net.minecraft.network.chat.Component>> render) {
        // Render while still on the source entity's thread. Only immutable event
        // data and completed components cross to the recipients' region threads.
        Map<String, String> recipients = new HashMap<>();
        Map<String, List<net.minecraft.network.chat.Component>> messages = new HashMap<>();
        for (String player : ONLINE_PLAYERS) {
            String option = subscriptions(player).get(name);
            if (option == null) continue;
            recipients.put(player, option);
            messages.computeIfAbsent(option, key -> {
                var output = render.apply(key);
                return output == null ? List.of() : List.copyOf(output);
            });
        }
        if (recipients.isEmpty()) return;
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            MinecraftServer server = MinecraftServer.getServer();
            for (var recipient : recipients.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(recipient.getKey());
                if (player == null) continue;
                List<net.minecraft.network.chat.Component> output = messages.get(recipient.getValue());
                if (!output.isEmpty()) player.getBukkitEntity().taskScheduler.schedule(entity -> {
                    for (var message : output) ((ServerPlayer) entity).sendSystemMessage(message);
                }, null, 1L);
            }
        });
    }

    public static void subscribe(String playerName, String name, String option) {
        LoggerOptions logger = loggerOptions(name);
        String accepted = option == null ? logger.defaultOption() : option;
        if (logger.strict() && !logger.options().contains(accepted)) throw new IllegalArgumentException("Invalid option: " + accepted);
        PLAYER_SUBSCRIPTIONS.compute(playerName, (key, old) -> {
            Map<String, String> next = new HashMap<>(old == null ? Map.of() : old);
            next.put(name, accepted);
            return Map.copyOf(next);
        });
        fun.bm.lophine.carpet.CarpetLoggerStorage.record(playerName, subscriptions(playerName));
        refreshMicroShapes(playerName);
    }

    public static boolean toggle(String playerName, String name) {
        loggerOptions(name);
        boolean[] subscribed = {false};
        PLAYER_SUBSCRIPTIONS.compute(playerName, (key, old) -> {
            Map<String, String> next = new HashMap<>(old == null ? Map.of() : old);
            if (next.remove(name) == null) {
                next.put(name, defaultOption(name));
                subscribed[0] = true;
            }
            return Map.copyOf(next);
        });
        fun.bm.lophine.carpet.CarpetLoggerStorage.record(playerName, subscriptions(playerName));
        refreshMicroShapes(playerName);
        clearOnlineHud(playerName);
        return subscribed[0];
    }

    public static void unsubscribe(String playerName, String name) {
        if (name != null) loggerOptions(name);
        PLAYER_SUBSCRIPTIONS.compute(playerName, (key, old) -> {
            if (name == null || old == null) return Map.of();
            Map<String, String> next = new HashMap<>(old);
            next.remove(name);
            return Map.copyOf(next);
        });
        fun.bm.lophine.carpet.CarpetLoggerStorage.record(playerName, subscriptions(playerName));
        refreshMicroShapes(playerName);
        clearOnlineHud(playerName);
    }

    private static void refreshMicroShapes(String name) {
        fun.bm.lophine.carpet.OrgHiddenPathProtocol.subscriptionChanged(name);
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getPlayerList() == null) return;
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player != null) player.getBukkitEntity().taskScheduler.schedule(entity -> {
            if (subscriptions(name).containsKey("microTiming")) fun.bm.lophine.carpet.TisMicroTimingMarkers.subscribed((ServerPlayer) entity);
            else fun.bm.lophine.carpet.TisMicroTimingMarkers.unsubscribed((ServerPlayer) entity);
        }, null, 1L);
    }

    private static void clearOnlineHud(String name) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player != null) player.getBukkitEntity().taskScheduler.schedule(entity -> clearHud((ServerPlayer) entity), null, 1L);
    }

    public static void refreshConfiguredDefaults(boolean initial) {
        Map<String, String> defaults = parseConfiguredDefaults(GeneralCompatConfig.defaultLoggers);
        configuredSubscriptions = defaults;
        if (initial) {
            PLAYER_SUBSCRIPTIONS.clear();
            SEEN_PLAYERS.clear();
            ONLINE_PLAYERS.clear();
            fun.bm.lophine.carpet.CarpetLoggerStorage.load();
        }
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
        String name = player.getScoreboardName();
        ONLINE_PLAYERS.add(name);
        var restored = fun.bm.lophine.carpet.CarpetLoggerStorage.joined(name, player.getUUID());
        if (SEEN_PLAYERS.add(name)) {
            if (GeneralCompatConfig.persistentLoggerSubscription && restored != null) {
                Map<String, String> accepted = new HashMap<>();
                restored.forEach((logger, option) -> {
                    LoggerOptions definition = LOGGERS.get(logger);
                    if (definition == null) return;
                    if (logger.equals("movement") && !fun.bm.lophine.carpet.TisMovementLogger.canSubscribe(player.createCommandSourceStack())) return;
                    String value = option.isEmpty() ? definition.defaultOption() : option;
                    if (!definition.strict() || definition.options().contains(value)) accepted.put(logger, value);
                });
                PLAYER_SUBSCRIPTIONS.put(name, Map.copyOf(accepted));
            } else if (configuredSubscriptions != null && !configuredSubscriptions.isEmpty()) {
                PLAYER_SUBSCRIPTIONS.putIfAbsent(name, configuredSubscriptions);
                fun.bm.lophine.carpet.CarpetLoggerStorage.record(name, subscriptions(name));
            }
        }
        // Join hooks can execute before the entity tick begins; capture on its owner.
        player.getBukkitEntity().taskScheduler.schedule(entity -> {
            fun.bm.lophine.carpet.TisMovementLogger.capture((ServerPlayer) entity);
            if (subscriptions(name).containsKey("microTiming")) fun.bm.lophine.carpet.TisMicroTimingMarkers.subscribed((ServerPlayer) entity);
        }, null, 1L);
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        ONLINE_PLAYERS.remove(player.getScoreboardName());
        fun.bm.lophine.carpet.TisMicroTimingMarkers.playerLeft(player.getUUID());
        fun.bm.lophine.carpet.TisMovementLogger.remove(player);
        clearHud(player);
    }

    public static void onGlobalTick(long tick) {
        fun.bm.lophine.carpet.OrgPlayerManagerLifecycle.tick(MinecraftServer.getServer());
        carpet.script.external.ScarpetRuntime.globalTick(MinecraftServer.getServer());
        fun.bm.lophine.carpet.CarpetServerClock.refresh(MinecraftServer.getServer());
        fun.bm.lophine.carpet.CarpetMobcaps.globalTick(MinecraftServer.getServer());
        fun.bm.lophine.carpet.TisSamplingLoggers.globalTick(MinecraftServer.getServer());
        fun.bm.lophine.carpet.TisMovementLogger.globalTick(MinecraftServer.getServer());
        fun.bm.lophine.carpet.TisMicroTimingMarkers.globalTick(MinecraftServer.getServer());
        fun.bm.lophine.carpet.AmsManagementCommands.tick(MinecraftServer.getServer(), tick);
        fun.bm.lophine.carpet.AmsCommandPermissionLevels.tick(MinecraftServer.getServer(), tick);
        int interval = Math.clamp(GeneralCompatConfig.HUDLoggerUpdateInterval, 1, 1000);
        if (tick % interval == 0L) onHudTick();
    }

    public static void onHudTick() {
        var packetCounts = fun.bm.lophine.carpet.CarpetPacketCounter.snapshot();
        if (PLAYER_SUBSCRIPTIONS.isEmpty() && carpet.script.external.ScarpetRuntime.HEADERS.isEmpty() && carpet.script.external.ScarpetRuntime.FOOTERS.isEmpty()) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, String> subscriptions = PLAYER_SUBSCRIPTIONS.getOrDefault(player.getScoreboardName(), Map.of());
            if (subscriptions.isEmpty() && !carpet.script.external.ScarpetRuntime.HEADERS.containsKey(player.getScoreboardName()) && !carpet.script.external.ScarpetRuntime.FOOTERS.containsKey(player.getScoreboardName())) {
                continue;
            }
            player.getBukkitEntity().taskScheduler.schedule((LivingEntity livingEntity) -> {
                if (livingEntity instanceof ServerPlayer scheduledPlayer) {
                    sendHud(server, scheduledPlayer, subscriptions, packetCounts);
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
        return "hud".equals(tickerID) ? Math.max(1, Math.min(1000, GeneralCompatConfig.HUDLoggerUpdateInterval)) : 1;
    }

    private static void sendHud(MinecraftServer server, ServerPlayer player, Map<String, String> subscriptions,
        fun.bm.lophine.carpet.CarpetPacketCounter.Counts packetCounts) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        subscriptions.forEach((loggerName, option) -> {
            switch (loggerName) {
                case "tps" -> lines.add(buildTpsLine(server));
                case "packets" -> {
                    lines.add(net.minecraft.network.chat.Component.literal("I/" + packetCounts.received() + " O/" + packetCounts.sent()));
                }
                case "mobcaps" -> {
                    net.minecraft.network.chat.Component line = buildMobcapsLine(player, option);
                    if (line != null) {
                        lines.add(line);
                    }
                }
                case "counter" -> lines.addAll(buildCounterLines(server, option));
                case "entityIdCounter" -> lines.addAll(fun.bm.lophine.carpet.TisSamplingLoggers.entityHud(option));
                case "lightQueue" -> lines.addAll(fun.bm.lophine.carpet.TisSamplingLoggers.lightHud(option, player));
                case "xcounter" -> lines.addAll(fun.bm.lophine.carpet.TisXpCounter.hud(option, server));
                case "lifetime" -> lines.addAll(fun.bm.lophine.carpet.TisLifetimeTracker.hud(option, player));
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
        var scriptFooter = carpet.script.external.ScarpetRuntime.FOOTERS.get(player.getScoreboardName());
        if (scriptFooter != null) {
            if (!lines.isEmpty()) footer.append(net.minecraft.network.chat.Component.literal("\n"));
            footer.append(scriptFooter);
        }
        var header = carpet.script.external.ScarpetRuntime.HEADERS.getOrDefault(player.getScoreboardName(), net.minecraft.network.chat.Component.empty());
        player.connection.send(new ClientboundTabListPacket(fun.bm.lophine.carpet.TisTranslations.translate(header, player), fun.bm.lophine.carpet.TisTranslations.translate(footer, player)));
    }

    public static void sendScarpetHud(ServerPlayer player) {
        sendHud(player.level().getServer(), player, PLAYER_SUBSCRIPTIONS.getOrDefault(player.getScoreboardName(), Map.of()), fun.bm.lophine.carpet.CarpetPacketCounter.displayed());
    }

    private static void clearHud(ServerPlayer player) {
        sendScarpetHud(player);
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
        String dimension = option == null || option.isBlank() || option.equalsIgnoreCase("dynamic")
            ? player.level().dimension().identifier().toString()
            : switch (option.toLowerCase(Locale.ROOT)) {
                case "overworld" -> "minecraft:overworld";
                case "nether" -> "minecraft:the_nether";
                case "end" -> "minecraft:the_end";
                default -> option.contains(":") ? option : "minecraft:" + option;
            };
        var snapshot = fun.bm.lophine.carpet.CarpetMobcaps.dimension(dimension);
        if (snapshot == null) return net.minecraft.network.chat.Component.literal("Mobcaps: unavailable").withStyle(ChatFormatting.DARK_GRAY);
        var counts = snapshot.counts();
        MutableComponent line = net.minecraft.network.chat.Component.literal("Mobcaps").withStyle(ChatFormatting.GRAY);
        for (MobCategory category : MobCategory.values()) {
            if (category == MobCategory.MISC) {
                continue;
            }
            int current = counts.getOrDefault(category, 0);
            int limit = snapshot.limits().getOrDefault(category, 0);
            line.append(net.minecraft.network.chat.Component.literal("  " + shortName(category) + " ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(net.minecraft.network.chat.Component.literal(current + "/" + limit).withStyle(categoryColor(current, limit)));
        }
        return line;
    }

    private static List<net.minecraft.network.chat.Component> buildCounterLines(MinecraftServer server, String option) {
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
            for (Component component : counter.format(server, false)) {
                lines.add(PaperAdventure.asVanilla(component));
            }
        }
        return lines;
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
                String requestedName = parts[0];
                String loggerName = LOGGERS.keySet().stream().filter(name -> name.equalsIgnoreCase(requestedName)).findFirst().orElse(requestedName);
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
        return LOGGERS.containsKey(loggerName);
    }

    private static @NotNull String defaultOption(String loggerName) {
        return loggerOptions(loggerName).defaultOption();
    }
}
