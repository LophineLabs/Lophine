package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.carpet.config.modules.WoolHopperCounterConfig;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public final class CarpetRuleRegistry {
    private static final Map<String, Binding> RULES = createBindings();
    private static final ThreadLocal<Map<String, Object>> LOADING = new ThreadLocal<>();

    public static AutoCloseable configurationView(java.util.function.Function<String, Object> reader) {
        Map<String, Object> previous = LOADING.get(), requested = new java.util.HashMap<>();
        for (Binding binding : RULES.values()) {
            Object value = reader.apply(binding.path());
            if (value != null) requested.put(binding.name(), value);
        }
        LOADING.set(Map.copyOf(requested));
        return () -> {
            if (previous == null) LOADING.remove();
            else LOADING.set(previous);
        };
    }

    private static boolean booleanValue(String name, boolean fallback) {
        Map<String, Object> values = LOADING.get();
        Object value = values == null ? null : values.get(name);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")))
            return Boolean.parseBoolean(text);
        return fallback;
    }

    private static String stringValue(String name, String fallback) {
        Map<String, Object> values = LOADING.get();
        Object value = values == null ? null : values.get(name);
        return value == null ? fallback : value.toString();
    }

    private CarpetRuleRegistry() {
    }

    public static Set<String> names() {
        return RULES.keySet();
    }

    public static Set<String> availableNames() {
        var result = new java.util.TreeSet<String>();
        for (String name : RULES.keySet()) {
            if (!CarpetRuleMetadata.isHidden(name) || OrgHiddenPlayerActions.enabled()) result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    public static void requireAvailable(String name) {
        if (!RULES.containsKey(name) || CarpetRuleMetadata.isHidden(name) && !OrgHiddenPlayerActions.enabled()) {
            throw new IllegalArgumentException("Unknown Carpet rule: " + name);
        }
    }

    public static Binding get(final String name) {
        Binding binding = RULES.get(name);
        if (binding == null) throw new IllegalArgumentException("Unknown Carpet rule: " + name);
        return binding;
    }

    public static ConfigsInstance config() {
        ConfigsInstance config = ConfigManager.getConfigs("lophine_carpet");
        if (config == null) throw new IllegalStateException("Carpet configuration is not initialized.");
        return config;
    }

    private static Map<String, Binding> createBindings() {
        Map<String, Binding> result = new TreeMap<>();
        for (Class<?> type : List.of(GeneralCompatConfig.class, FakePlayerCompatConfig.class, WoolHopperCounterConfig.class)) {
            ConfigClassInfo module = type.getAnnotation(ConfigClassInfo.class);
            for (Field field : type.getDeclaredFields()) {
                ConfigInfo info = field.getAnnotation(ConfigInfo.class);
                if (info == null || !Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                    continue;
                List<String> path = new ArrayList<>();
                String category = module.category().getBaseKeyName();
                if (category != null) path.add(category);
                path.addAll(List.of(module.directory()));
                path.add(module.name());
                path.addAll(List.of(info.directory()));
                path.add(info.name());
                result.put(info.name(), new Binding(info.name(), String.join(".", path), field));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public record Binding(String name, String path, Field field) {
        public Object value() {
            try {
                return this.field.get(null);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }

        public Object parse(final String text) {
            var definition = CarpetRuleMetadata.get(this.name);
            if (!definition.project().equals("Carpet Org Addition") && definition.strict() && !definition.options().isEmpty()
                    && !definition.options().contains(text))
                throw new IllegalArgumentException("Expected one of: " + String.join(", ", definition.options()));
            Class<?> type = this.field.getType();
            Object value;
            if (type == boolean.class) {
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("Expected true or false.");
                }
                value = Boolean.parseBoolean(text);
            } else if (type == int.class) {
                value = Integer.parseInt(text);
            } else if (type == long.class) {
                value = Long.parseLong(text);
            } else if (type == float.class) {
                value = Float.parseFloat(text);
            } else if (type == double.class) {
                value = Double.parseDouble(text);
            } else if (type == String.class) {
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                var upstream = CarpetRuleMetadata.get(this.name);
                boolean enumValue = !Set.of("", "String", "boolean", "int", "long", "double", "float").contains(upstream.type());
                value = switch (this.name) {
                    case "scriptsAppStore" -> text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
                    case "fakePlayerPickUpController" -> switch (lower) {
                        case "mainhandonly" -> "MainHandOnly";
                        case "nopickup" -> "NoPickUp";
                        default -> lower;
                    };
                    case "sendPlayerDeathLocation" -> switch (lower) {
                        case "realplayeronly" -> "realPlayerOnly";
                        case "fakeplayeronly" -> "fakePlayerOnly";
                        default -> lower;
                    };
                    case "maxPlayerBlockInteractionRangeScope", "maxPlayerEntityInteractionRangeScope", "lightUpdates",
                         "betterTotemOfUndying", "blockDropsDirectlyEnterInventory", "largeBundle",
                         "violentNetherPortalCreation",
                         "noteBlockChunkLoader", "pistonBlockChunkLoader", "fakePlayerKeepInventoryCondition",
                         "openPlayerInventory", "amsUpdateSuppressionCrashFix", "blueSkullController",
                         "itemAntiExplosion", "channelingIgnoreConditions", "renewableCoral", "thickFungusGrowth",
                         "chainStone",
                         "forceOpenContainer", "carpetCommandPermissionLevel", "microTimingDyeMarker",
                         "microTimingTarget", "microTimingTickDivision" -> lower;
                    default ->
                            enumValue || upstream.project().equals("Carpet Org Addition") || (this.name.startsWith("command") || this.name.startsWith("playerCommand") || this.name.equals("loggerMovement") || this.name.equals("fakePlayerRemoteSpawning")) && Set.of("true", "false", "ops", "0", "1", "2", "3", "4").contains(lower) ? lower : text;
                };
            } else if (List.class.isAssignableFrom(type)) {
                value = text.isBlank() || "none".equalsIgnoreCase(text) ? List.of()
                        : Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            } else {
                throw new IllegalArgumentException("Unsupported rule type: " + type.getSimpleName());
            }
            validate(this.name, value);
            return value;
        }
    }

    public static void validate(final String name, final Object value) {
        var metadata = CarpetRuleMetadata.get(name);
        String optionValue = metadata.type().equals("String") ? value.toString() : value.toString().toLowerCase(java.util.Locale.ROOT);
        if (metadata.project().equals("Carpet Org Addition") && metadata.strict() && !metadata.options().isEmpty() && !(value instanceof Boolean)
                && !metadata.options().contains(optionValue)) {
            throw new IllegalArgumentException("Expected one of: " + String.join(", ", metadata.options()));
        }
        Set<String> choices = switch (name) {
            case "renewableCoral" -> Set.of("false", "true", "expanded");
            case "thickFungusGrowth" -> Set.of("false", "random", "all");
            case "chainStone" -> Set.of("false", "true", "stick_to_all");
            case "forceOpenContainer" -> Set.of("false", "any", "shulker_box");
            case "carpetCommandPermissionLevel" -> Set.of("ops", "2", "4");
            case "commandDistance", "commandLog", "commandRemoveEntity", "commandSleep", "commandRefresh",
                 "commandRaycast", "fillCommandModeEnhance", "commandPlayerChunkLoadController", "commandHere",
                 "commandWhere", "commandGoto", "commandGetPlayerSkull", "commandGetHeldItemID",
                 "commandGetSaveSize", "commandGetSystemInfo", "commandKillMe", "commandRuleSearch", "commandSpectator",
                 "commandCreeper",
                 "commandXpTransfer", "commandProfile", "commandTick", "commandPlayer", "tickCommandPermission",
                 "commandRaid",
                 "commandItemShadowing", "commandLocations", "commandNavigate", "fakePlayerRemoteSpawning",
                 "commandPlayerActionPerTick",
                 "playerCommandOpenPlayerInventory", "loggerMovement", "commandSpeedTest", "commandSpawn",
                 "commandInfo", "commandManipulate", "commandDraw", "commandTrackAI", "commandPerimeterInfo" ->
                    Set.of("true", "false", "ops", "0", "1", "2", "3", "4");
            case "microTimingDyeMarker" -> Set.of("true", "false", "clear");
            case "microTimingTarget" -> Set.of("marker_only", "labelled", "in_range", "all");
            case "microTimingTickDivision" -> Set.of("world_timer", "player_action");
            case "amsTranslationMode" -> Set.of("client", "server");
            case "amsUpdateSuppressionCrashFix" -> Set.of("false", "true", "silence");
            case "updateSuppressionSimulator" ->
                    Set.of("false", "true", "stackoverflowerror", "outofmemoryerror", "classcastexception", "illegalargumentexception", "illegalstateexception");
            case "blueSkullController" -> Set.of("vanilla", "surely", "never");
            case "itemAntiExplosion" -> Set.of("false", "true", "no_blast_wave");
            case "channelingIgnoreConditions" -> Set.of("false", "ignore_weather", "ignore_weather_and_sky");
            case "violentNetherPortalCreation" -> Set.of("false", "replaceable", "all");
            case "maxPlayerBlockInteractionRangeScope", "maxPlayerEntityInteractionRangeScope" ->
                    Set.of("server", "global");
            case "fakePlayerPickUpController" -> Set.of("false", "mainhandonly", "nopickup");
            case "sendPlayerDeathLocation" -> Set.of("false", "all", "realplayeronly", "fakeplayeronly");
            case "betterTotemOfUndying" -> Set.of("vanilla", "inventory", "inventory_with_shulker_box");
            case "blockDropsDirectlyEnterInventory" -> Set.of("false", "true", "custom");
            case "largeBundle" -> Set.of("false", "9x3", "9x6");
            case "lightUpdates" -> Set.of("on", "suppressed", "ignored", "off");
            case "noteBlockChunkLoader" -> Set.of("false", "bone_block", "wither_skeleton_skull", "note_block");
            case "pistonBlockChunkLoader" -> Set.of("false", "bone_block", "bedrock", "all");
            case "openPlayerInventory" -> Set.of("false", "fake_player", "any_player");
            case "playerCommandOpenPlayerInventoryOption" ->
                    Set.of("fake_player", "online_player", "non_whitelist", "all_player");
            case "fakePlayerKeepInventoryCondition" -> Set.of("unconditional", "killed_by_player_or_the_void");
            default ->
                    name.startsWith("command") && value instanceof String ? Set.of("true", "false", "ops", "0", "1", "2", "3", "4") : Set.of();
        };
        if (!choices.isEmpty() && !choices.contains(value.toString().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Expected one of: " + String.join(", ", choices));
        }
        if (name.equals("chunkUpdatePacketThreshold") && ((int) value < 2 || (int) value > 65536))
            throw new IllegalArgumentException("Expected 2 to 65536.");
        if (name.equals("hardcodeTNTangle")) {
            double angle = (double) value;
            if (!(angle == -1 || angle >= 0 && angle < Math.PI * 2))
                throw new IllegalArgumentException("Expected -1 or an angle from 0 up to 2 pi.");
        }
        if (name.equals("quasiConnectivity")) {
            int maximum = Integer.MAX_VALUE;
            var server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null && server.isReady()) {
                maximum = 1;
                for (var world : server.getAllLevels()) maximum = Math.max(maximum, world.getHeight() - 1);
            }
            if ((int) value < 0 || (int) value > maximum)
                throw new IllegalArgumentException("Expected a quasi connectivity range from 0 to " + maximum + ".");
        }
        if (name.equals("structureBlockIgnored")) {
            var id = net.minecraft.resources.Identifier.tryParse(value.toString());
            if (id == null) throw new IllegalArgumentException("Unknown block: " + value);
            var server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null && server.isReady() && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))
                throw new IllegalArgumentException("Unknown block: " + value);
        }
        if ((name.equals("pushLimit") || name.equals("railPowerLimit") || name.equals("sculkSensorRange")) && ((int) value < 1 || (int) value > 1024))
            throw new IllegalArgumentException("Expected 1 to 1024.");
        if (name.equals("forceloadLimit") && ((int) value < 1 || (int) value > 20_000_000))
            throw new IllegalArgumentException("Expected 1 to 20000000.");
        if ((name.equals("maxEntityCollisions") || name.equals("pingPlayerListLimit") || name.equals("fillLimit")) && (int) value < 0)
            throw new IllegalArgumentException("Expected a nonnegative value.");
        if ((name.equals("viewDistance") || name.equals("simulationDistance")) && ((int) value < 0 || (int) value > 32))
            throw new IllegalArgumentException("Expected 0 to 32.");
        if (name.equals("easyGetPitcherPod") && (int) value != 0 && ((int) value < 2 || (int) value > 100))
            throw new IllegalArgumentException("Expected 0 or 2 to 100.");
        if (name.equals("creativeFlySpeed") && !((double) value >= 0))
            throw new IllegalArgumentException("Expected a nonnegative flight multiplier.");
        if (name.equals("creativeFlyDrag") && (!Double.isFinite((double) value) || (double) value < 0 || (double) value > 1))
            throw new IllegalArgumentException("Expected drag from 0 to 1.");
        if (name.equals("renewableNetheriteScrap") && (!Double.isFinite((double) value) || (double) value < 0 || (double) value > 1))
            throw new IllegalArgumentException("Expected a finite probability from 0 to 1.");
        if (name.equals("structureBlockLimit") && (int) value < 48)
            throw new IllegalArgumentException("Minimum structure block limit is 48.");
        if (name.equals("chunkTickSpeed") && (int) value < 0)
            throw new IllegalArgumentException("Expected a nonnegative chunk tick speed.");
        if (name.equals("oakBalloonPercent") && ((int) value < -1 || (int) value > 100))
            throw new IllegalArgumentException("Expected -1 or a percentage from 0 to 100.");
        if (name.equals("beaconRangeExpand") && (int) value > 1024)
            throw new IllegalArgumentException("Maximum beacon range expansion is 1024.");
        if (name.equals("maxPlayerBlockInteractionRange") || name.equals("maxPlayerEntityInteractionRange")
                || name.equals("maxClientInteractionReachDistance")) {
            double range = (double) value;
            if (!(range == -1.0 || range >= 0.0 && range <= 512.0))
                throw new IllegalArgumentException("Expected -1 or a range from 0 to 512.");
        }
        if (name.equals("enhancedWorldEater")) {
            double resistance = (double) value;
            if (!(resistance == -1.0 || resistance >= 0.0 && resistance <= 16.0))
                throw new IllegalArgumentException("Expected -1 or a resistance from 0 to 16.");
        }
        if (name.equals("experimentalMinecartSpeed") && ((int) value < -1 || (int) value > 1000))
            throw new IllegalArgumentException("Expected a minecart speed from -1 to 1000.");
        if (name.equals("itemPickupRangeExpand") && (int) value < 0)
            throw new IllegalArgumentException("Expected a nonnegative pickup expansion.");
        if (name.equals("maxLinesPerPage") && (int) value <= 0)
            throw new IllegalArgumentException("Expected a positive page size.");
        if (name.equals("perfPermissionLevel") && (int) value != 2 && (int) value != 4)
            throw new IllegalArgumentException("Expected permission level 2 or 4.");
        if (name.equals("flippinCactusSoundEffect") && ((int) value < 0 || (int) value > 5))
            throw new IllegalArgumentException("Expected a sound effect from 0 to 5.");
        if (name.equals("updateSuppressionBlock") && (int) value < -1)
            throw new IllegalArgumentException("Expected -1 or a nonnegative update allowance.");
        if (name.equals("largeBarrel") && (boolean) value && fun.bm.lophine.config.modules.function.ContainerExpansionConfig.barrelRows != 3) {
            throw new IllegalArgumentException("largeBarrel requires barrelRows=3 for two 27-slot barrels.");
        }
        if ((name.equals("blockChunkLoaderTimeController") || name.equals("blockChunkLoaderRangeController")) && ((int) value < 1 || (int) value > 300))
            throw new IllegalArgumentException("Expected a value from 1 to 300.");
        if (name.equals("synchronizedLightThread") && (boolean) value
                && Set.of("suppressed", "off").contains(stringValue("lightUpdates", GeneralCompatConfig.lightUpdates).toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Light synchronization requires lightUpdates=on or ignored.");
        }
        if (name.equals("lightUpdates") && booleanValue("synchronizedLightThread", GeneralCompatConfig.synchronizedLightThread)
                && Set.of("suppressed", "off").contains(value.toString().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Disable synchronizedLightThread before suppressing light updates.");
        }
        if ((name.equals("entityIdCounterLoggerSamplingDuration") || name.equals("lightQueueLoggerSamplingDuration")) && (int) value <= 0)
            throw new IllegalArgumentException("Expected a positive sampling duration.");
        if (name.equals("speedTestCommandMaxTestSize") && (int) value <= 0)
            throw new IllegalArgumentException("Expected a positive test size.");
        if (name.equals("manipulateBlockLimit") && (int) value <= 0)
            throw new IllegalArgumentException("Expected a positive block limit.");
        if (name.equals("tileTickLimit") && (int) value <= 0)
            throw new IllegalArgumentException("Expected a positive scheduled tick limit.");
        if (name.equals("HUDLoggerUpdateInterval") && ((int) value < 1 || (int) value > 1000))
            throw new IllegalArgumentException("Expected a HUD interval from 1 to 1000.");
        if (name.equals("netherPortalMaxSize") && ((int) value < 2 || (int) value > 384))
            throw new IllegalArgumentException("Expected a portal size from 2 to 384.");
        if (name.equals("setAnvilExperienceConsumptionLimit")) {
            int limit = (int) value;
            if (limit != -1 && (limit < 1 || limit > 10000))
                throw new IllegalArgumentException("Expected -1 or a limit from 1 to 10000.");
        }
        if (name.equals("tntRandomRange")) {
            double range = (double) value;
            if (!(range == -1.0D || range >= 0.0D))
                throw new IllegalArgumentException("Expected -1 or a nonnegative range.");
            if (range != -1.0D && !booleanValue("optimizedTNT", GeneralCompatConfig.optimizedTNT))
                throw new IllegalArgumentException("Enable optimizedTNT first.");
        }
        if (Set.of("blockEventPacketRange", "explosionPacketRange", "voidDamageAmount").contains(name) && !((double) value >= 0))
            throw new IllegalArgumentException("Expected a nonnegative value.");
        if (name.equals("snowMeltMinLightLevel") && (int) value < 0)
            throw new IllegalArgumentException("Expected a nonnegative light threshold.");
        if (name.equals("maxBlockPlaceDistance") && !((double) value == -1 || (double) value >= 0 && (double) value <= 256))
            throw new IllegalArgumentException("Expected -1 or a placement distance from 0 to 256.");
        if (name.equals("customPiglinBarteringTime") && (long) value < -1)
            throw new IllegalArgumentException("Expected -1 or a nonnegative bartering time.");
        if (name.equals("fakePlayerMaxItemOperationCount") && (int) value != -1 && (int) value < 1)
            throw new IllegalArgumentException("Expected -1 or a positive action count.");
        if (name.equals("voidRelatedAltitude") && !((double) value < 0))
            throw new IllegalArgumentException("Expected a negative altitude.");
        if (name.equals("renewableElytra") && !((double) value >= 0 && (double) value <= 1))
            throw new IllegalArgumentException("Expected a probability from 0 to 1.");
        if (Set.of("spawnBabyProbably", "spawnJockeyProbably", "spawnLeaderZombieProbably").contains(name)
                && !((double) value >= -1 && (double) value <= 1))
            throw new IllegalArgumentException("Expected a probability from -1 to 1.");
        if (name.equals("tntFuseDuration") && ((int) value < 0 || (int) value > Short.MAX_VALUE))
            throw new IllegalArgumentException("Expected a fuse from 0 to 32767.");
        if (name.equals("xpTrackingDistance") && !((double) value >= 0 && (double) value <= 128))
            throw new IllegalArgumentException("Expected a tracking distance from 0 to 128.");
        if (name.equals("voidDamageIgnorePlayer")) {
            String modes = value.toString();
            if (!Set.of("true", "false").contains(modes) && (modes.isEmpty()
                    || Arrays.stream(modes.split(",")).anyMatch(mode -> net.minecraft.world.level.GameType.byName(mode, null) == null)))
                throw new IllegalArgumentException("Expected true, false, or a comma separated list of game modes.");
        }
        if (name.equals("stackableShulkerBoxes") && !Set.of("true", "false").contains(value.toString().toLowerCase(java.util.Locale.ROOT))) {
            int size = Integer.parseInt(value.toString());
            if (size < 2 || size > 64)
                throw new IllegalArgumentException("Expected false, true, or a size from 2 to 64.");
        }
    }
}
