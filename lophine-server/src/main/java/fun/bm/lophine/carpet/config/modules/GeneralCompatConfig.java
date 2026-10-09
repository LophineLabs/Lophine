package fun.bm.lophine.carpet.config.modules;

import fun.bm.lophine.carpet.CarpetProtocalDataBase;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "general", directory = {"carpet"})
public class GeneralCompatConfig {
    @ConfigInfo(name = "commandFinder", section = "org")
    public static volatile String commandFinder = "true";

    @ConfigInfo(name = "playerCommandOpenPlayerInventoryOption", section = "org")
    public static volatile String playerCommandOpenPlayerInventoryOption = "fake_player";

    @ConfigInfo(name = "commandMail", section = "org")
    public static volatile String commandMail = "ops";

    @ConfigInfo(name = "commandPlayerManager", section = "org")
    public static volatile String commandPlayerManager = "ops";

    @ConfigInfo(name = "playerManagerForceComment", section = "org")
    public static volatile boolean playerManagerForceComment = false;

    @ConfigInfo(name = "fakePlayerSpawnMemoryLeakFix", section = "org")
    public static volatile boolean fakePlayerSpawnMemoryLeakFix = false;

    @ConfigInfo(name = "wetExplosionReintroduced", section = "tis")
    public static volatile boolean wetExplosionReintroduced = false;

    @ConfigInfo(name = "displayPlayerSummoner", section = "org")
    public static volatile boolean displayPlayerSummoner = false;

    @ConfigInfo(name = "commandPlayerAction", section = "org")
    public static volatile String commandPlayerAction = "ops";

    @ConfigInfo(name = "fakePlayerActionKeepItem", section = "org")
    public static volatile boolean fakePlayerActionKeepItem = false;

    @ConfigInfo(name = "fakePlayerMaxItemOperationCount", section = "org")
    public static volatile int fakePlayerMaxItemOperationCount = 3;

    @ConfigInfo(name = "quickSettingFakePlayerCraft", section = "org")
    public static volatile String quickSettingFakePlayerCraft = "false";


    @ConfigInfo(name = "playerCommandTeleportFakePlayer", section = "org")
    public static volatile String playerCommandTeleportFakePlayer = "false";

    @ConfigInfo(name = "playerCommandSummonMannequin", section = "org")
    public static volatile String playerCommandSummonMannequin = "false";


    @ConfigInfo(name = "cauldronBlockItemInteractFix", section = "tis")
    public static volatile boolean cauldronBlockItemInteractFix = false;

    @ConfigInfo(name = "chunkUpdatePacketThreshold", section = "tis")
    public static volatile int chunkUpdatePacketThreshold = 64;

    @ConfigInfo(name = "deobfuscateCrashReportStackTrace", section = "tis")
    public static volatile boolean deobfuscateCrashReportStackTrace = false;

    @ConfigInfo(name = "entityBrainMemoryUnfreedFix", section = "tis")
    public static volatile boolean entityBrainMemoryUnfreedFix = false;

    @ConfigInfo(name = "entityChunkSectionIndexXOverflowFix", section = "tis")
    public static volatile boolean entityChunkSectionIndexXOverflowFix = false;

    @ConfigInfo(name = "minecartFullDropBackport", section = "tis")
    public static volatile boolean minecartFullDropBackport = false;

    @ConfigInfo(name = "yeetAsyncTaskExecutionDelay", section = "tis")
    public static volatile boolean yeetAsyncTaskExecutionDelay = false;


    @ConfigInfo(name = "visualizeProjectileLoggerEnabled", section = "tis")
    public static volatile boolean visualizeProjectileLoggerEnabled = false;


    @ConfigInfo(name = "commandScript")
    public static volatile String commandScript = "true";

    @ConfigInfo(name = "commandScriptACE")
    public static volatile String commandScriptACE = "ops";

    @ConfigInfo(name = "scriptsAutoload")
    public static volatile boolean scriptsAutoload = true;

    @ConfigInfo(name = "scriptsDebugging")
    public static volatile boolean scriptsDebugging = false;

    @ConfigInfo(name = "scriptsOptimization")
    public static volatile boolean scriptsOptimization = true;

    @ConfigInfo(name = "scriptsAppStore")
    public static volatile String scriptsAppStore = "gnembon/scarpet/contents/programs";


    @ConfigInfo(name = "microTimingDyeMarker", section = "tis")
    public static volatile String microTimingDyeMarker = "true";

    @ConfigInfo(name = "microTimingTarget", section = "tis")
    public static volatile String microTimingTarget = "marker_only";

    @ConfigInfo(name = "microTimingTickDivision", section = "tis")
    public static volatile String microTimingTickDivision = "world_timer";


    @ConfigInfo(name = "amsNetworkProtocol", section = "ams")
    public static volatile boolean amsNetworkProtocol = false;

    @ConfigInfo(name = "amsTranslationMode", section = "ams")
    public static volatile String amsTranslationMode = "client";

    @ConfigInfo(name = "commandAmspDebug", section = "ams")
    public static volatile String commandAmspDebug = "false";

    @ConfigInfo(name = "commandGetClientPlayerFps", section = "ams")
    public static volatile String commandGetClientPlayerFps = "false";

    @ConfigInfo(name = "commandPacketInternetGroper", section = "ams")
    public static volatile String commandPacketInternetGroper = "false";

    @ConfigInfo(name = "commandCustomAntiFireItems", section = "ams")
    public static volatile String commandCustomAntiFireItems = "false";

    @ConfigInfo(name = "commandAnvilInteractionDisabled", section = "ams")
    public static volatile String commandAnvilInteractionDisabled = "false";

    @ConfigInfo(name = "commandCustomMovableBlock", section = "ams")
    public static volatile String commandCustomMovableBlock = "false";

    @ConfigInfo(name = "commandCustomBlockBlastResistance", section = "ams")
    public static volatile String commandCustomBlockBlastResistance = "false";

    @ConfigInfo(name = "commandCustomBlockHardness", section = "ams")
    public static volatile String commandCustomBlockHardness = "false";

    @ConfigInfo(name = "commandPlayerLeader", section = "ams")
    public static volatile String commandPlayerLeader = "false";

    @ConfigInfo(name = "commandPlayerNoNetherPortalTeleport", section = "ams")
    public static volatile String commandPlayerNoNetherPortalTeleport = "false";

    @ConfigInfo(name = "commandCustomCommandPermissionLevel", section = "ams")
    public static volatile String commandCustomCommandPermissionLevel = "false";

    @ConfigInfo(name = "commandSetPlayerPose", section = "ams")
    public static volatile String commandSetPlayerPose = "false";

    @ConfigInfo(name = "commandAtSomeOnePlayer", section = "ams")
    public static volatile String commandAtSomeOnePlayer = "false";

    @ConfigInfo(name = "commandCarpetExtensionModWikiHyperlink", section = "ams")
    public static volatile String commandCarpetExtensionModWikiHyperlink = "false";


    @ConfigInfo(name = "commandLifeTime", section = "tis")
    public static volatile String commandLifeTime = "true";

    @ConfigInfo(name = "lifeTimeTrackerConsidersMobcap", section = "tis")
    public static volatile boolean lifeTimeTrackerConsidersMobcap = true;


    @ConfigInfo(name = "commandPerimeterInfo")
    public static volatile String commandPerimeterInfo = "true";


    @ConfigInfo(name = "commandTrackAI")
    public static volatile String commandTrackAI = "ops";

    @ConfigInfo(name = "creativeFlySpeed")
    public static volatile double creativeFlySpeed = 1.0;

    @ConfigInfo(name = "creativeFlyDrag")
    public static volatile double creativeFlyDrag = 0.09;

    @ConfigInfo(name = "cleanLogs")
    public static volatile boolean cleanLogs = false;

    @ConfigInfo(name = "commandDraw")
    public static volatile String commandDraw = "ops";

    @ConfigInfo(name = "customBlockUpdateSuppressor", section = "ams")
    public static volatile String customBlockUpdateSuppressor = "none";

    @ConfigInfo(name = "renewableNetheriteScrap", section = "ams")
    public static volatile double renewableNetheriteScrap = 0.0;

    @ConfigInfo(name = "superLeash", section = "ams")
    public static volatile boolean superLeash = false;

    @ConfigInfo(name = "redstoneComponentSound", section = "ams")
    public static volatile boolean redstoneComponentSound = false;

    @ConfigInfo(name = "preventAdministratorCheat", section = "ams")
    public static volatile boolean preventAdministratorCheat = false;

    @ConfigInfo(name = "testRule", section = "ams")
    public static volatile boolean testRule = false;

    @ConfigInfo(name = "persistentLoggerSubscription", section = "tis")
    public static volatile boolean persistentLoggerSubscription = false;

    @ConfigInfo(name = "commandManipulate", section = "tis")
    public static volatile String commandManipulate = "false";

    @ConfigInfo(name = "manipulateBlockLimit", section = "tis")
    public static volatile int manipulateBlockLimit = 1000000;

    @ConfigInfo(name = "commandInfo")
    public static volatile String commandInfo = "true";

    @ConfigInfo(name = "carpets")
    public static volatile boolean carpets = false;


    @ConfigInfo(name = "commandSpawn")
    public static volatile String commandSpawn = "ops";

    @ConfigInfo(name = "mobcapsDisplayIgnoreMisc", section = "tis")
    public static volatile boolean mobcapsDisplayIgnoreMisc = false;

    @ConfigInfo(name = "commandSpeedTest", section = "tis")
    public static volatile String commandSpeedTest = "false";

    @ConfigInfo(name = "speedTestCommandMaxTestSize", section = "tis")
    public static volatile int speedTestCommandMaxTestSize = 10;

    @ConfigInfo(name = "openPlayerInventory", section = "org")
    public static volatile String openPlayerInventory = "false";

    @ConfigInfo(name = "playerCommandOpenPlayerInventoryGcaStyle", section = "org")
    public static volatile boolean playerCommandOpenPlayerInventoryGcaStyle = true;

    @ConfigInfo(name = "playerCommandOpenPlayerInventory", section = "org")
    public static volatile String playerCommandOpenPlayerInventory = "false";

    @ConfigInfo(name = "playerCommandCloseScreen", section = "org")
    public static volatile boolean playerCommandCloseScreen = false;

    @ConfigInfo(name = "loggerMovement", section = "tis")
    public static volatile String loggerMovement = "ops";

    @ConfigInfo(name = "entityIdCounterLoggerSamplingDuration", section = "tis")
    public static volatile int entityIdCounterLoggerSamplingDuration = 100;

    @ConfigInfo(name = "lightQueueLoggerSamplingDuration", section = "tis")
    public static volatile int lightQueueLoggerSamplingDuration = 60;

    @ConfigInfo(name = "fakePlayerNamePrefix", section = "tis")
    public static volatile String fakePlayerNamePrefix = "#none";

    @ConfigInfo(name = "fakePlayerNameSuffix", section = "tis")
    public static volatile String fakePlayerNameSuffix = "#none";

    @ConfigInfo(name = "fakePlayerRemoteSpawning", section = "tis")
    public static volatile String fakePlayerRemoteSpawning = "true";

    @ConfigInfo(name = "commandPlayerActionPerTick", section = "tis")
    public static volatile String commandPlayerActionPerTick = "false";


    @ConfigInfo(name = "simpleUpdateSkipper", section = "org")
    public static volatile boolean simpleUpdateSkipper = false;

    @ConfigInfo(name = "CCEUpdateSuppression", section = "org")
    public static volatile String CCEUpdateSuppression = "false";

    @ConfigInfo(name = "suppressionMismatchInDestroyBlockPosWarn", section = "org")
    public static volatile boolean suppressionMismatchInDestroyBlockPosWarn = false;

    @ConfigInfo(name = "hopperXpCounters", section = "tis")
    public static volatile boolean hopperXpCounters = false;


    @ConfigInfo(name = "commandItemShadowing", section = "org")
    public static volatile String commandItemShadowing = "ops";

    @ConfigInfo(name = "commandLocations", section = "org")
    public static volatile String commandLocations = "ops";

    @ConfigInfo(name = "commandNavigate", section = "org")
    public static volatile String commandNavigate = "true";

    @ConfigInfo(name = "maxLinesPerPage", section = "org")
    public static volatile int maxLinesPerPage = 10;

    @ConfigInfo(name = "updateSkippingSimulator", section = "tis")
    public static volatile boolean updateSkippingSimulator = false;

    @ConfigInfo(name = "updateSuppressionSimulator", section = "tis")
    public static volatile String updateSuppressionSimulator = "false";

    @ConfigInfo(name = "soundSuppressionSimulator", section = "tis")
    public static volatile boolean soundSuppressionSimulator = false;

    @ConfigInfo(name = "yeetIdleMspt", section = "tis")
    public static volatile boolean yeetIdleMspt = false;


    @ConfigInfo(name = "allowSpawningOfflinePlayers")
    public static volatile boolean allowSpawningOfflinePlayers = true;

    @ConfigInfo(name = "allowListingFakePlayers")
    public static volatile boolean allowListingFakePlayers = false;

    @ConfigInfo(name = "commandRaid", section = "tis")
    public static volatile String commandRaid = "true";

    @ConfigInfo(name = "tickCommandCarpetfied", section = "tis")
    public static volatile boolean tickCommandCarpetfied = false;

    @ConfigInfo(name = "tickCommandEnhance", section = "tis")
    public static volatile boolean tickCommandEnhance = false;

    @ConfigInfo(name = "tickFreezeDeepCommand", section = "tis")
    public static volatile boolean tickFreezeDeepCommand = false;

    @ConfigInfo(name = "tickProfilerCommandsReintroduced", section = "tis")
    public static volatile boolean tickProfilerCommandsReintroduced = false;

    @ConfigInfo(name = "tickWarpCommandAsAnAlias", section = "tis")
    public static volatile boolean tickWarpCommandAsAnAlias = false;

    @ConfigInfo(name = "commandProfile")
    public static volatile String commandProfile = "true";
    @ConfigInfo(name = "commandXpTransfer", section = "org")
    public static volatile String commandXpTransfer = "ops";

    @ConfigInfo(name = "ultraSecretSetting", section = "tis")
    public static volatile String ultraSecretSetting = "false";

    @ConfigInfo(name = "stopCommandDoubleConfirmation", section = "tis")
    public static volatile boolean stopCommandDoubleConfirmation = false;

    @ConfigInfo(name = "fillCommandModeEnhance", section = "tis")
    public static volatile String fillCommandModeEnhance = "true";

    @ConfigInfo(name = "commandRaycast", section = "tis")
    public static volatile String commandRaycast = "ops";

    @ConfigInfo(name = "commandKillMe", section = "org")
    public static volatile String commandKillMe = "ops";

    @ConfigInfo(name = "commandRuleSearch", section = "org")
    public static volatile String commandRuleSearch = "ops";

    @ConfigInfo(name = "commandSpectator", section = "org")
    public static volatile String commandSpectator = "ops";

    @ConfigInfo(name = "commandCreeper", section = "org")
    public static volatile String commandCreeper = "false";

    @ConfigInfo(name = "commandRefresh", section = "tis")
    public static volatile String commandRefresh = "true";

    @ConfigInfo(name = "fancyFakePlayerName", section = "ams")
    public static volatile String fancyFakePlayerName = "false";

    @ConfigInfo(name = "fakePlayerUseOfflinePlayerUUID", section = "ams")
    public static volatile boolean fakePlayerUseOfflinePlayerUUID = false;

    @ConfigInfo(name = "onlyOpCanSpawnRealPlayerInWhitelist", section = "ams")
    public static volatile boolean onlyOpCanSpawnRealPlayerInWhitelist = false;

    @ConfigInfo(name = "welcomeMessage", section = "ams")
    public static volatile boolean welcomeMessage = false;

    @ConfigInfo(name = "opPlayerNoCheat", section = "tis")
    public static volatile boolean opPlayerNoCheat = false;

    @ConfigInfo(name = "commandRemoveEntity", section = "tis")
    public static volatile String commandRemoveEntity = "ops";

    @ConfigInfo(name = "commandSleep", section = "tis")
    public static volatile String commandSleep = "ops";


    @ConfigInfo(name = "applyToolEffectsImmediately", section = "org")
    public static volatile boolean applyToolEffectsImmediately = false;

    @ConfigInfo(name = "autoSyncPlayerStatus", section = "org")
    public static volatile boolean autoSyncPlayerStatus = false;

    @ConfigInfo(name = "recordPlayerCommand", section = "org")
    public static volatile boolean recordPlayerCommand = false;

    @ConfigInfo(name = "fakePlayerAutoRestock", section = "org")
    public static volatile boolean fakePlayerAutoRestock = false;

    @ConfigInfo(name = "fakePlayerShulkerBoxItemHandling", section = "org")
    public static volatile boolean fakePlayerShulkerBoxItemHandling = false;

    @ConfigInfo(name = "fakePlayerKeepInventory", section = "org")
    public static volatile boolean fakePlayerKeepInventory = false;

    @ConfigInfo(name = "fakePlayerKeepInventoryCondition", section = "org")
    public static volatile String fakePlayerKeepInventoryCondition = "unconditional";

    @ConfigInfo(name = "instantCommandBlock", section = "tis")
    public static volatile boolean instantCommandBlock = false;

    @ConfigInfo(name = "entityPathNavigationStuckDetectionUseRealTimeReintroduced", section = "tis")
    public static volatile boolean entityPathNavigationStuckDetectionUseRealTimeReintroduced = false;

    @ConfigInfo(name = "zombifiedPiglinDropLootIfAngryReintroduced", section = "tis")
    public static volatile boolean zombifiedPiglinDropLootIfAngryReintroduced = false;

    @ConfigInfo(name = "keepMobInLazyChunks", section = "tis")
    public static volatile boolean keepMobInLazyChunks = false;

    @ConfigInfo(name = "overspawningReintroduced", section = "tis")
    public static volatile boolean overspawningReintroduced = false;

    @ConfigInfo(name = "commandPlayerChunkLoadController", section = "ams")
    public static volatile String commandPlayerChunkLoadController = "false";

    @ConfigInfo(name = "creativePlayersLoadChunks")
    public static volatile boolean creativePlayersLoadChunks = true;

    @ConfigInfo(name = "noteBlockChunkLoader", section = "ams")
    public static volatile String noteBlockChunkLoader = "false";

    @ConfigInfo(name = "pistonBlockChunkLoader", section = "ams")
    public static volatile String pistonBlockChunkLoader = "false";

    @ConfigInfo(name = "bellBlockChunkLoader", section = "ams")
    public static volatile boolean bellBlockChunkLoader = false;

    @ConfigInfo(name = "blockChunkLoaderKeepWorldTickUpdate", section = "ams")
    public static volatile boolean blockChunkLoaderKeepWorldTickUpdate = false;

    @ConfigInfo(name = "keepWorldTickUpdate", section = "ams")
    public static volatile boolean keepWorldTickUpdate = false;

    @ConfigInfo(name = "blockChunkLoaderTimeController", section = "ams")
    public static volatile int blockChunkLoaderTimeController = 300;

    @ConfigInfo(name = "blockChunkLoaderRangeController", section = "ams")
    public static volatile int blockChunkLoaderRangeController = 3;

    @ConfigInfo(name = "lightUpdates", section = "tis")
    public static volatile String lightUpdates = "on";

    @ConfigInfo(name = "synchronizedLightThread", section = "tis")
    public static volatile boolean synchronizedLightThread = false;

    @ConfigInfo(name = "largeBarrel", section = "tis")
    public static volatile boolean largeBarrel = false;

    @ConfigInfo(name = "quickShulker", section = "org")
    public static volatile boolean quickShulker = false;

    @ConfigInfo(name = "shulkerBoxStackable", section = "org")
    public static volatile boolean shulkerBoxStackable = false;

    @ConfigInfo(name = "openVillagerInventory", section = "org")
    public static volatile boolean openVillagerInventory = false;

    @ConfigInfo(name = "flippinCactusExtras", section = "ams")
    public static volatile boolean flippinCactusExtras = false;

    @ConfigInfo(name = "flippinCactusSoundEffect", section = "ams")
    public static volatile int flippinCactusSoundEffect = 0;

    @ConfigInfo(name = "perfPermissionLevel")
    public static volatile int perfPermissionLevel = 4;

    @ConfigInfo(name = "superSecretSetting")
    public static volatile boolean superSecretSetting = false;

    @ConfigInfo(name = "updateSuppressionBlock")
    public static volatile int updateSuppressionBlock = -1;

    @ConfigInfo(name = "commandLog")
    public static volatile String commandLog = "true";
    @ConfigInfo(name = "endPortalChunkLoadDisabled", section = "ams")
    public static volatile boolean endPortalChunkLoadDisabled = false;

    @ConfigInfo(name = "preventServerPause", section = "ams")
    public static volatile boolean preventServerPause = false;

    @ConfigInfo(name = "customizedNetherPortal", section = "ams")
    public static volatile boolean customizedNetherPortal = false;

    @ConfigInfo(name = "stringDupeReintroduced", section = "ams")
    public static volatile boolean stringDupeReintroduced = false;

    @ConfigInfo(name = "largeBundle", section = "ams")
    public static volatile String largeBundle = "false";

    @ConfigInfo(name = "creativeHitRemoveEntity", section = "tis")
    public static volatile boolean creativeHitRemoveEntity = false;

    @ConfigInfo(name = "noToolBreak", section = "org")
    public static volatile boolean noToolBreak = false;

    @ConfigInfo(name = "betterTotemOfUndying", section = "org")
    public static volatile String betterTotemOfUndying = "vanilla";

    @ConfigInfo(name = "knockbackStick", section = "org")
    public static volatile boolean knockbackStick = false;

    @ConfigInfo(name = "disableCreativeContainerDrops", section = "org")
    public static volatile boolean disableCreativeContainerDrops = false;

    @ConfigInfo(name = "blockDropsDirectlyEnterInventory", section = "org")
    public static volatile String blockDropsDirectlyEnterInventory = "false";

    @ConfigInfo(name = "itemPickupRangeExpand", section = "org")
    public static volatile int itemPickupRangeExpand = 0;

    @ConfigInfo(name = "itemPickupRangeExpandPlayerControl", section = "org")
    public static volatile boolean itemPickupRangeExpandPlayerControl = false;

    @ConfigInfo(name = "naturalSpawningUse13Heightmap", section = "tis")
    public static volatile boolean naturalSpawningUse13Heightmap = false;

    @ConfigInfo(name = "naturalSpawningUse13HeightmapExtra", section = "tis")
    public static volatile boolean naturalSpawningUse13HeightmapExtra = false;

    @ConfigInfo(name = "preciseEntityPlacement", section = "tis")
    public static volatile boolean preciseEntityPlacement = false;

    @ConfigInfo(name = "dispensersFireDragonBreath", section = "tis")
    public static volatile boolean dispensersFireDragonBreath = false;

    @ConfigInfo(name = "redstoneDustRandomUpdateOrder", section = "tis")
    public static volatile boolean redstoneDustRandomUpdateOrder = false;

    @ConfigInfo(name = "redstoneDustRepeaterComparatorIgnoreUpwardsStateUpdate", section = "tis")
    public static volatile boolean redstoneDustRepeaterComparatorIgnoreUpwardsStateUpdate = false;

    @ConfigInfo(name = "maxPlayerBlockInteractionRange", section = "ams")
    public static volatile double maxPlayerBlockInteractionRange = -1.0;

    @ConfigInfo(name = "maxPlayerEntityInteractionRange", section = "ams")
    public static volatile double maxPlayerEntityInteractionRange = -1.0;

    @ConfigInfo(name = "maxPlayerBlockInteractionRangeScope", section = "ams")
    public static volatile String maxPlayerBlockInteractionRangeScope = "server";

    @ConfigInfo(name = "maxPlayerEntityInteractionRangeScope", section = "ams")
    public static volatile String maxPlayerEntityInteractionRangeScope = "server";

    @ConfigInfo(name = "enhancedWorldEater", section = "ams")
    public static volatile double enhancedWorldEater = -1.0;

    @ConfigInfo(name = "fakePlayerNoScoreboardCounter", section = "ams")
    public static volatile boolean fakePlayerNoScoreboardCounter = false;

    @ConfigInfo(name = "fakePlayerPickUpController", section = "ams")
    public static volatile String fakePlayerPickUpController = "false";

    @ConfigInfo(name = "fertilizableSmallFlower", section = "ams")
    public static volatile boolean fertilizableSmallFlower = false;

    @ConfigInfo(name = "sendPlayerDeathLocation", section = "ams")
    public static volatile String sendPlayerDeathLocation = "false";

    @ConfigInfo(name = "maxChainUpdateDepth", section = "ams")
    public static volatile int maxChainUpdateDepth = -1;

    @ConfigInfo(name = "phantomSpawnAlert", section = "ams")
    public static volatile boolean phantomSpawnAlert = false;

    @ConfigInfo(name = "experimentalMinecartSpeed", section = "ams")
    public static volatile int experimentalMinecartSpeed = -1;

    @me.earthme.luminol.config.flags.DoNotLoad(when = me.earthme.luminol.enums.EnumLoadType.RELOAD)
    @ConfigInfo(name = "experimentalMinecartEnabled", section = "ams")
    public static volatile boolean experimentalMinecartEnabled = false;

    @ConfigInfo(name = "chunkTickSpeed", section = "tis")
    public static volatile int chunkTickSpeed = 1;

    @ConfigInfo(name = "elytraFireworkKeepLeashConnection", section = "tis")
    public static volatile boolean elytraFireworkKeepLeashConnection = false;

    @ConfigInfo(name = "sandDupingFix", section = "tis")
    public static volatile boolean sandDupingFix = false;

    @ConfigInfo(name = "oakBalloonPercent", section = "tis")
    public static volatile int oakBalloonPercent = -1;

    @ConfigInfo(name = "violentNetherPortalCreation", section = "tis")
    public static volatile String violentNetherPortalCreation = "false";

    @ConfigInfo(name = "renewableDragonEgg", section = "tis")
    public static volatile boolean renewableDragonEgg = false;

    @ConfigInfo(name = "beaconRangeExpand", section = "org")
    public static volatile int beaconRangeExpand = 0;

    @ConfigInfo(name = "beaconWorldHeight", section = "org")
    public static volatile boolean beaconWorldHeight = false;

    @ConfigInfo(name = "limitPhantomSpawn", section = "org")
    public static volatile boolean limitPhantomSpawn = false;

    @ConfigInfo(name = "commandHere", section = "ams")
    public static volatile String commandHere = "false";

    @ConfigInfo(name = "commandWhere", section = "ams")
    public static volatile String commandWhere = "false";

    @ConfigInfo(name = "commandGoto", section = "ams")
    public static volatile String commandGoto = "false";

    @ConfigInfo(name = "commandGetPlayerSkull", section = "ams")
    public static volatile String commandGetPlayerSkull = "false";

    @ConfigInfo(name = "commandGetHeldItemID", section = "ams")
    public static volatile String commandGetHeldItemID = "false";

    @ConfigInfo(name = "commandGetSaveSize", section = "ams")
    public static volatile String commandGetSaveSize = "false";

    @ConfigInfo(name = "commandGetSystemInfo", section = "ams")
    public static volatile String commandGetSystemInfo = "false";

    @ConfigInfo(name = "HUDLoggerUpdateInterval", section = "tis")
    public static volatile int HUDLoggerUpdateInterval = 20;

    @ConfigInfo(name = "canMineSpawner", section = "org")
    public static volatile boolean canMineSpawner = false;

    @ConfigInfo(name = "tooledTNT", section = "tis")
    public static volatile boolean tooledTNT = false;

    public static boolean hasSilkTouch(net.minecraft.world.item.ItemStack stack) {
        for (var entry : stack.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getKey().is(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) && entry.getIntValue() > 0)
                return true;
        }
        return false;
    }

    public static net.minecraft.world.item.ItemStack explosionTool(net.minecraft.world.level.Explosion explosion) {
        if (!tooledTNT) return net.minecraft.world.item.ItemStack.EMPTY;
        return carpet.script.external.ScarpetAttribution.explosionTool(explosion);
    }

    @ConfigInfo(name = "tileTickLimit", section = "tis")
    public static volatile int tileTickLimit = 65536;

    @ConfigInfo(name = "failSoftBlockStateParsing", section = "tis")
    public static volatile boolean failSoftBlockStateParsing = false;

    @ConfigInfo(name = "chatMessageLengthLimitUnlocked", section = "tis")
    public static volatile boolean chatMessageLengthLimitUnlocked = false;

    @ConfigInfo(name = "craftableEnchantedGoldenApples", section = "ams")
    public static volatile boolean craftableEnchantedGoldenApples = false;

    @ConfigInfo(name = "craftableElytra", section = "ams")
    public static volatile boolean craftableElytra = false;

    @ConfigInfo(name = "betterCraftablePolishedBlackStoneButton", section = "ams")
    public static volatile boolean betterCraftablePolishedBlackStoneButton = false;

    @ConfigInfo(name = "rottenFleshBurnedIntoLeather", section = "ams")
    public static volatile boolean rottenFleshBurnedIntoLeather = false;

    @ConfigInfo(name = "craftableCarvedPumpkin", section = "ams")
    public static volatile boolean craftableCarvedPumpkin = false;

    private static List<Boolean> appliedRecipeRules;
    private static Boolean appliedLargeEnderChest;

    public static List<Boolean> recipeRuleValues() {
        return List.of(betterCraftableBoneBlock, betterCraftableDispenser, craftableEnchantedGoldenApples,
                craftableElytra, betterCraftablePolishedBlackStoneButton, rottenFleshBurnedIntoLeather, craftableCarvedPumpkin);
    }

    @ConfigInfo(name = "stackableDiscounts", section = "ams")
    public static volatile boolean stackableDiscounts = false;
    private static Boolean appliedStackableDiscounts;

    @ConfigInfo(name = "commandDistance")
    public static volatile String commandDistance = "true";

    public static final ScopedValue<Boolean> CHANNELING_TRIDENT = ScopedValue.newInstance();

    @ConfigInfo(name = "channelingIgnoreConditions", section = "org")
    public static volatile String channelingIgnoreConditions = "false";

    @ConfigInfo(name = "gazeDisguiseEquipmentExtended", section = "tis")
    public static volatile boolean gazeDisguiseEquipmentExtended = false;

    @ConfigInfo(name = "renewableDragonHead", section = "tis")
    public static volatile boolean renewableDragonHead = false;

    @ConfigInfo(name = "easyRefreshTrades", section = "ams")
    public static volatile boolean easyRefreshTrades = false;

    public static boolean channelingIgnoresWeather() {
        return CHANNELING_TRIDENT.orElse(false) && ("ignore_weather".equalsIgnoreCase(channelingIgnoreConditions)
                || "ignore_weather_and_sky".equalsIgnoreCase(channelingIgnoreConditions));
    }

    public static boolean channelingIgnoresSky() {
        return CHANNELING_TRIDENT.orElse(false) && "ignore_weather_and_sky".equalsIgnoreCase(channelingIgnoreConditions);
    }

    @ConfigInfo(name = "movableBlockEntities")
    public static volatile boolean movableBlockEntities = false;

    @ConfigInfo(name = "flippinCactus")
    public static volatile boolean flippinCactus = false;

    @ConfigInfo(name = "rotatorBlock")
    public static volatile boolean rotatorBlock = false;

    @ConfigInfo(name = "strongLeash", section = "ams")
    public static volatile boolean strongLeash = false;

    @ConfigInfo(name = "regeneratingDragonEgg", section = "ams")
    public static volatile boolean regeneratingDragonEgg = false;

    @ConfigInfo(name = "cryingObsidianNetherPortal", section = "ams")
    public static volatile boolean cryingObsidianNetherPortal = false;

    @ConfigInfo(name = "creativeNetherWaterPlacement", section = "tis")
    public static volatile boolean creativeNetherWaterPlacement = false;

    @ConfigInfo(name = "netherPortalMaxSize", section = "tis")
    public static volatile int netherPortalMaxSize = 21;

    @ConfigInfo(name = "sharedVillagerDiscounts", section = "ams")
    public static volatile boolean sharedVillagerDiscounts = false;

    @ConfigInfo(name = "debugNbtQueryNoPermission", section = "tis")
    public static volatile boolean debugNbtQueryNoPermission = false;

    @ConfigInfo(name = "flattenTriangularDistribution", section = "tis")
    public static volatile boolean flattenTriangularDistribution = false;

    @ConfigInfo(name = "leaderZombieSpawnWithMaxHealthDisabled", section = "tis")
    public static volatile boolean leaderZombieSpawnWithMaxHealthDisabled = false;

    @ConfigInfo(name = "moveableReinforcedDeepslate", section = "tis")
    public static volatile boolean moveableReinforcedDeepslate = false;

    @ConfigInfo(name = "vaultBlacklistDisabled", section = "tis")
    public static volatile boolean vaultBlacklistDisabled = false;

    @ConfigInfo(name = "blowUpEverything", section = "ams")
    public static volatile boolean blowUpEverything = false;

    @ConfigInfo(name = "blueSkullController", section = "ams")
    public static volatile String blueSkullController = "vanilla";

    @ConfigInfo(name = "itemAntiExplosion", section = "ams")
    public static volatile String itemAntiExplosion = "false";

    @ConfigInfo(name = "superZombieDoctor", section = "ams")
    public static volatile boolean superZombieDoctor = false;

    @ConfigInfo(name = "easyCompost", section = "ams")
    public static volatile boolean easyCompost = false;

    @ConfigInfo(name = "setAnvilExperienceConsumptionLimit", section = "org")
    public static volatile int setAnvilExperienceConsumptionLimit = -1;

    @ConfigInfo(name = "language")
    public static volatile String language = "en_us";

    @ConfigInfo(name = "amsUpdateSuppressionCrashFix", section = "ams")
    public static volatile String amsUpdateSuppressionCrashFix = "false";

    @ConfigInfo(name = "yeetUpdateSuppressionCrash", section = "tis")
    public static volatile boolean yeetUpdateSuppressionCrash = false;

    @ConfigInfo(name = "dustTrapdoorReintroduced", section = "tis")
    public static volatile boolean dustTrapdoorReintroduced = false;

    @ConfigInfo(name = "shulkerBoxCCEReintroduced", section = "tis")
    public static volatile boolean shulkerBoxCCEReintroduced = false;

    @ConfigInfo(name = "instantBlockUpdaterReintroduced", section = "tis")
    public static volatile boolean instantBlockUpdaterReintroduced = false;

    @ConfigInfo(name = "commandTick")
    public static volatile String commandTick = "ops";

    public static boolean tickCommandEnabled() {
        return !"false".equalsIgnoreCase(commandTick);
    }

    @ConfigInfo(name = "creativeNoClip")
    public static volatile boolean creativeNoClip = false;

    @ConfigInfo(name = "optimizedDragonRespawn", section = "ams")
    public static volatile boolean optimizedDragonRespawn = false;

    @ConfigInfo(name = "antiSpamDisabled", section = "tis")
    public static volatile boolean antiSpamDisabled = false;

    @ConfigInfo(name = "blockPlacementIgnoreEntity", section = "tis")
    public static volatile boolean blockPlacementIgnoreEntity = false;

    @ConfigInfo(name = "creativeOpenContainerForcibly", section = "tis")
    public static volatile boolean creativeOpenContainerForcibly = false;

    @ConfigInfo(name = "creativeOneHitKill", section = "ams")
    public static volatile boolean creativeOneHitKill = false;

    @ConfigInfo(name = "observerNoDetection", section = "tis")
    public static volatile boolean observerNoDetection = false;

    @ConfigInfo(name = "bambooModelNoOffset", section = "ams")
    public static volatile boolean bambooModelNoOffset = false;

    @ConfigInfo(name = "creativeNoItemCooldown", section = "tis")
    public static volatile boolean creativeNoItemCooldown = false;

    @ConfigInfo(name = "ctrlQCraftingFix")
    public static volatile boolean ctrlQCraftingFix = false;

    @ConfigInfo(name = "carpetAlwaysSetDefault", section = "ams")
    public static volatile boolean carpetAlwaysSetDefault = false;

    @ConfigInfo(name = "placementRotationFix")
    public static volatile boolean placementRotationFix = false;

    @ConfigInfo(name = "tntDoNotUpdate")
    public static volatile boolean tntDoNotUpdate = false;

    @ConfigInfo(name = "totallyNoBlockUpdate", section = "tis")
    public static volatile boolean totallyNoBlockUpdate = false;

    @ConfigInfo(name = "tiscmNetworkProtocol", section = "tis")
    public static volatile boolean tiscmNetworkProtocol = false;

    @ConfigInfo(name = "hopperNoItemCost", section = "tis")
    public static volatile boolean hopperNoItemCost = false;

    @ConfigInfo(name = "explosionNoBlockDamage")
    public static volatile boolean explosionNoBlockDamage = false;

    @ConfigInfo(name = "noCreeperBlockBreaking")
    public static volatile boolean noCreeperBlockBreaking = false;

    @ConfigInfo(name = "noGhastBlockBreaking")
    public static volatile boolean noGhastBlockBreaking = false;

    @ConfigInfo(name = "disableBlazeFire")
    public static volatile boolean disableBlazeFire = false;

    @ConfigInfo(name = "disableGhastFire")
    public static volatile boolean disableGhastFire = false;

    @ConfigInfo(name = "optimizedTNTHighPriority", section = "tis")
    public static volatile boolean optimizedTNTHighPriority = false;

    @ConfigInfo(name = "tntPrimerMomentumRemoved")
    public static volatile boolean tntPrimerMomentumRemoved = false;

    @ConfigInfo(name = "tntIgnoreRedstoneSignal", section = "tis")
    public static volatile boolean tntIgnoreRedstoneSignal = false;

    @ConfigInfo(name = "tntDupingFix", section = "tis")
    public static volatile boolean tntDupingFix = false;

    @ConfigInfo(name = "interactionUpdates")
    public static volatile boolean interactionUpdates = true;

    @ConfigInfo(name = "xpNoCooldown")
    public static volatile boolean xpNoCooldown = false;

    @ConfigInfo(name = "liquidDamageDisabled")
    public static volatile boolean liquidDamageDisabled = false;

    @ConfigInfo(name = "silverFishDropGravel")
    public static volatile boolean silverFishDropGravel = false;

    @ConfigInfo(name = "moreBlueSkulls")
    public static volatile boolean moreBlueSkulls = false;

    @ConfigInfo(name = "sculkSensorRange")
    public static volatile int sculkSensorRange = 8;

    @ConfigInfo(name = "missingTools")
    public static volatile boolean missingTools = false;

    @ConfigInfo(name = "pingPlayerListLimit")
    public static volatile int pingPlayerListLimit = 12;

    @ConfigInfo(name = "customMOTD")
    public static volatile String customMOTD = "_";

    @ConfigInfo(name = "xpFromExplosions")
    public static volatile boolean xpFromExplosions = false;

    @ConfigInfo(name = "movableAmethyst")
    public static volatile boolean movableAmethyst = false;

    @ConfigInfo(name = "endPortalOpenedSoundDisabled", section = "tis")
    public static volatile boolean endPortalOpenedSoundDisabled = false;

    @ConfigInfo(name = "fluidDestructionDisabled", section = "tis")
    public static volatile boolean fluidDestructionDisabled = false;

    @ConfigInfo(name = "poiUpdates", section = "tis")
    public static volatile boolean poiUpdates = true;

    @ConfigInfo(name = "enchantCommandNoRestriction", section = "tis")
    public static volatile boolean enchantCommandNoRestriction = false;

    @ConfigInfo(name = "entityMomentumLoss", section = "tis")
    public static volatile boolean entityMomentumLoss = true;

    @ConfigInfo(name = "dispenserNoItemCost", section = "tis")
    public static volatile boolean dispenserNoItemCost = false;

    @ConfigInfo(name = "explosionNoEntityInfluence", section = "tis")
    public static volatile boolean explosionNoEntityInfluence = false;

    @ConfigInfo(name = "scheduledRandomTickCactus", section = "ams")
    public static volatile boolean scheduledRandomTickCactus = false;

    @ConfigInfo(name = "scheduledRandomTickBamboo", section = "ams")
    public static volatile boolean scheduledRandomTickBamboo = false;

    @ConfigInfo(name = "scheduledRandomTickChorusFlower", section = "ams")
    public static volatile boolean scheduledRandomTickChorusFlower = false;

    @ConfigInfo(name = "scheduledRandomTickSugarCane", section = "ams")
    public static volatile boolean scheduledRandomTickSugarCane = false;

    @ConfigInfo(name = "scheduledRandomTickStem", section = "ams")
    public static volatile boolean scheduledRandomTickStem = false;

    @ConfigInfo(name = "scheduledRandomTickAllPlants", section = "ams")
    public static volatile boolean scheduledRandomTickAllPlants = false;

    @ConfigInfo(name = "netherWaterPlacement", section = "ams")
    public static volatile boolean netherWaterPlacement = false;

    @ConfigInfo(name = "bambooCollisionBoxDisabled", section = "ams")
    public static volatile boolean bambooCollisionBoxDisabled = false;

    @ConfigInfo(name = "useItemCooldownDisabled", section = "ams")
    public static volatile boolean useItemCooldownDisabled = false;

    @ConfigInfo(name = "enderDragonNoDestroyBlock", section = "ams")
    public static volatile boolean enderDragonNoDestroyBlock = false;

    @ConfigInfo(name = "disableBatCanSpawn", section = "org")
    public static volatile boolean disableBatCanSpawn = false;

    @ConfigInfo(name = "disableWaterFreezes", section = "org")
    public static volatile boolean disableWaterFreezes = false;

    @ConfigInfo(name = "turtleEggFastHatch", section = "org")
    public static volatile boolean turtleEggFastHatch = false;

    @ConfigInfo(name = "farmlandPreventStepping", section = "org")
    public static volatile boolean farmlandPreventStepping = false;

    @ConfigInfo(name = "bindingCurseInvalidation", section = "org")
    public static volatile boolean bindingCurseInvalidation = false;

    @ConfigInfo(name = "peacefulCreeper", section = "org")
    public static volatile boolean peacefulCreeper = false;

    @ConfigInfo(name = "staringEndermanNotAngry", section = "org")
    public static volatile boolean staringEndermanNotAngry = false;

    @ConfigInfo(name = "healthNotFullCanEat", section = "org")
    public static volatile boolean healthNotFullCanEat = false;

    @ConfigInfo(name = "turtleEggFastMine", section = "org")
    public static volatile boolean turtleEggFastMine = false;

    @ConfigInfo(name = "disableRespawnBlocksExplode", section = "org")
    public static volatile boolean disableRespawnBlocksExplode = false;

    @ConfigInfo(name = "fireworkRocketUseCooldown", section = "org")
    public static volatile boolean fireworkRocketUseCooldown = false;

    @ConfigInfo(name = "noCakeEating", section = "ams")
    public static volatile boolean noCakeEating = false;

    @ConfigInfo(name = "sneakToEatCake", section = "ams")
    public static volatile boolean sneakToEatCake = false;

    @ConfigInfo(name = "shulkerHitLevitationDisabled", section = "ams")
    public static volatile boolean shulkerHitLevitationDisabled = false;

    @ConfigInfo(name = "immuneShulkerBullet", section = "ams")
    public static volatile boolean immuneShulkerBullet = false;

    @ConfigInfo(name = "noEnchantedGoldenAppleEating", section = "ams")
    public static volatile boolean noEnchantedGoldenAppleEating = false;

    @ConfigInfo(name = "easyMineDragonEgg", section = "ams")
    public static volatile boolean easyMineDragonEgg = false;

    @ConfigInfo(name = "endermanPickUpDisabled", section = "ams")
    public static volatile boolean endermanPickUpDisabled = false;

    @ConfigInfo(name = "endermanTeleportRandomlyDisabled", section = "ams")
    public static volatile boolean endermanTeleportRandomlyDisabled = false;

    @ConfigInfo(name = "renewableBlackstone")
    public static volatile boolean renewableBlackstone = false;

    @ConfigInfo(name = "renewableDeepslate")
    public static volatile boolean renewableDeepslate = false;

    @ConfigInfo(name = "explosionPacketRange", section = "tis")
    public static volatile double explosionPacketRange = 64.0D;

    @ConfigInfo(name = "renewableSponges")
    public static volatile boolean renewableSponges = false;

    @ConfigInfo(name = "desertShrubs")
    public static volatile boolean desertShrubs = false;

    @ConfigInfo(name = "pushLimit")
    public static volatile int pushLimit = 12;

    @ConfigInfo(name = "railPowerLimit")
    public static volatile int railPowerLimit = 9;

    @ConfigInfo(name = "forceloadLimit")
    public static volatile int forceloadLimit = 256;

    @ConfigInfo(name = "infiniteTrades", section = "ams")
    public static volatile boolean infiniteTrades = false;

    @ConfigInfo(name = "villagerInfiniteTrade", section = "org")
    public static volatile boolean villagerInfiniteTrade = false;

    @ConfigInfo(name = "infiniteDurability", section = "ams")
    public static volatile boolean infiniteDurability = false;

    @ConfigInfo(name = "noFamilyPlanning", section = "ams")
    public static volatile boolean noFamilyPlanning = false;

    @ConfigInfo(name = "undyingCoral", section = "ams")
    public static volatile boolean undyingCoral = false;

    @ConfigInfo(name = "safeFlight", section = "ams")
    public static volatile boolean safeFlight = false;

    @ConfigInfo(name = "invulnerable", section = "ams")
    public static volatile boolean invulnerable = false;

    @ConfigInfo(name = "quickVillagerLevelUp", section = "ams")
    public static volatile boolean quickVillagerLevelUp = false;

    @ConfigInfo(name = "fullMoonEveryDay", section = "ams")
    public static volatile boolean fullMoonEveryDay = false;

    @ConfigInfo(name = "fakePeace", section = "ams")
    public static volatile String fakePeace = "false";

    @ConfigInfo(name = "ironGolemNoDropFlower", section = "ams")
    public static volatile boolean ironGolemNoDropFlower = false;

    @ConfigInfo(name = "easyMaxLevelBeacon", section = "ams")
    public static volatile boolean easyMaxLevelBeacon = false;

    @ConfigInfo(name = "persistentParrots")
    public static volatile boolean persistentParrots = false;

    @ConfigInfo(name = "maxEntityCollisions")
    public static volatile int maxEntityCollisions = 0;

    @ConfigInfo(name = "lightningKillsDropsFix")
    public static volatile boolean lightningKillsDropsFix = false;

    @ConfigInfo(name = "xpTrackingDistance", section = "tis")
    public static volatile double xpTrackingDistance = 8.0;

    @ConfigInfo(name = "witherSpawnedSoundDisabled", section = "tis")
    public static volatile boolean witherSpawnedSoundDisabled = false;

    @ConfigInfo(name = "snowMeltMinLightLevel", section = "tis")
    public static volatile int snowMeltMinLightLevel = 12;

    @ConfigInfo(name = "turtleEggTrampledDisabled", section = "tis")
    public static volatile boolean turtleEggTrampledDisabled = false;

    @ConfigInfo(name = "voidRelatedAltitude", section = "tis")
    public static volatile double voidRelatedAltitude = -64.0;

    @ConfigInfo(name = "voidDamageAmount", section = "tis")
    public static volatile double voidDamageAmount = 4.0;

    @ConfigInfo(name = "voidDamageIgnorePlayer", section = "tis")
    public static volatile String voidDamageIgnorePlayer = "false";

    @ConfigInfo(name = "undeadDontBurnInSunlight", section = "tis")
    public static volatile boolean undeadDontBurnInSunlight = false;

    @ConfigInfo(name = "disableDamageImmunity")
    public static volatile boolean disableDamageImmunity = false;

    @ConfigInfo(name = "notDamageEnderPearl", section = "org")
    public static volatile boolean notDamageEnderPearl = false;

    @ConfigInfo(name = "reusableSmithingTemplate", section = "org")
    public static volatile String reusableSmithingTemplate = "false";

    @ConfigInfo(name = "disableFurnaceDropExperience", section = "org")
    public static volatile boolean disableFurnaceDropExperience = false;

    @ConfigInfo(name = "hopperSuctionDisabled", section = "ams")
    public static volatile boolean hopperSuctionDisabled = false;

    @ConfigInfo(name = "safePointedDripstone", section = "ams")
    public static volatile boolean safePointedDripstone = false;

    @ConfigInfo(name = "pointedDripstoneCollisionBoxDisabled", section = "ams")
    public static volatile boolean pointedDripstoneCollisionBoxDisabled = false;

    @ConfigInfo(name = "sneakToEditSign", section = "ams")
    public static volatile boolean sneakToEditSign = false;

    @ConfigInfo(name = "meekEnderman", section = "ams")
    public static volatile boolean meekEnderman = false;

    @ConfigInfo(name = "furnaceSmeltingTimeController", section = "ams")
    public static volatile int furnaceSmeltingTimeController = -1;

    @ConfigInfo(name = "fasterMovement", section = "ams")
    public static volatile String fasterMovement = "VANILLA";

    @ConfigInfo(name = "fasterMovementController", section = "ams")
    public static volatile String fasterMovementController = "all";

    @ConfigInfo(name = "easyWitherSkeletonSkullDrop", section = "ams")
    public static volatile boolean easyWitherSkeletonSkullDrop = false;

    @ConfigInfo(name = "witchRedstoneDustDropController", section = "ams")
    public static volatile int witchRedstoneDustDropController = -1;

    @ConfigInfo(name = "witchGlowstoneDustDropController", section = "ams")
    public static volatile int witchGlowstoneDustDropController = -1;

    @ConfigInfo(name = "jebSheepDropRandomColorWool", section = "ams")
    public static volatile boolean jebSheepDropRandomColorWool = false;

    @ConfigInfo(name = "cakeBlockDropOnBreak", section = "ams")
    public static volatile boolean cakeBlockDropOnBreak = false;

    @ConfigInfo(name = "easyGetPitcherPod", section = "ams")
    public static volatile int easyGetPitcherPod = 0;

    @ConfigInfo(name = "creativeShulkerBoxDropsDisabled", section = "ams")
    public static volatile boolean creativeShulkerBoxDropsDisabled = false;

    @ConfigInfo(name = "mitePearl", section = "ams")
    public static volatile boolean mitePearl = false;

    @ConfigInfo(name = "kirinArm", section = "ams")
    public static volatile boolean kirinArm = false;

    @ConfigInfo(name = "superBow", section = "ams")
    public static volatile boolean superBow = false;

    @ConfigInfo(name = "tntPowerController", section = "ams")
    public static volatile double tntPowerController = -1.0;

    @ConfigInfo(name = "truePeacefulMode", section = "org")
    public static volatile boolean truePeacefulMode = false;

    @ConfigInfo(name = "disableMobPeacefulDespawn", section = "org")
    public static volatile boolean disableMobPeacefulDespawn = false;

    @ConfigInfo(name = "disableWindChargeEffect", section = "org")
    public static volatile boolean disableWindChargeEffect = false;

    @ConfigInfo(name = "itemEntitySkipMovementDisabled", section = "tis")
    public static volatile boolean itemEntitySkipMovementDisabled = false;

    @ConfigInfo(name = "toughWitherRose", section = "tis")
    public static volatile boolean toughWitherRose = false;

    @ConfigInfo(name = "structureBlockDoNotPreserveFluid", section = "tis")
    public static volatile boolean structureBlockDoNotPreserveFluid = false;

    @ConfigInfo(name = "renewableElytra", section = "tis")
    public static volatile double renewableElytra = 0.0;

    @ConfigInfo(name = "entityTrackerDistance", section = "tis")
    public static volatile int entityTrackerDistance = -1;

    @ConfigInfo(name = "entityTrackerInterval", section = "tis")
    public static volatile int entityTrackerInterval = -1;

    @ConfigInfo(name = "repeaterHalfDelay", section = "tis")
    public static volatile boolean repeaterHalfDelay = false;

    @ConfigInfo(name = "breedableParrots", section = "ams")
    public static volatile String breedableParrots = "none";

    @ConfigInfo(name = "quasiConnectivity")
    public static volatile int quasiConnectivity = 1;

    @ConfigInfo(name = "huskSpawningInTemples")
    public static volatile boolean huskSpawningInTemples = false;

    @ConfigInfo(name = "shulkerSpawningInEndCities")
    public static volatile boolean shulkerSpawningInEndCities = false;

    @ConfigInfo(name = "piglinsSpawningInBastions")
    public static volatile boolean piglinsSpawningInBastions = false;

    @ConfigInfo(name = "antiCheatDisabled")
    public static volatile boolean antiCheatDisabled = false;

    @ConfigInfo(name = "stackableShulkerBoxes")
    public static volatile String stackableShulkerBoxes = "false";

    @ConfigInfo(name = "hardcodeTNTangle")
    public static volatile double hardcodeTNTangle = -1.0;

    @ConfigInfo(name = "mergeTNT")
    public static volatile boolean mergeTNT = false;

    @ConfigInfo(name = "creativeInstantTame", section = "tis")
    public static volatile boolean creativeInstantTame = false;

    @ConfigInfo(name = "openSeedPermission", section = "org")
    public static volatile boolean openSeedPermission = false;

    @ConfigInfo(name = "openTpPermission", section = "org")
    public static volatile boolean openTpPermission = false;

    @ConfigInfo(name = "openGameRulePermission", section = "org")
    public static volatile boolean openGameRulePermission = false;

    @ConfigInfo(name = "forceOpenContainer", section = "org")
    public static volatile String forceOpenContainer = "false";

    @ConfigInfo(name = "villagerHeal", section = "org")
    public static volatile boolean villagerHeal = false;

    @ConfigInfo(name = "fakePlayerHeal", section = "org")
    public static volatile boolean fakePlayerHeal = false;

    @ConfigInfo(name = "playerDropsNotDespawning", section = "org")
    public static volatile boolean playerDropsNotDespawning = false;

    @ConfigInfo(name = "totemOfUndyingInvincibleTime", section = "org")
    public static volatile boolean totemOfUndyingInvincibleTime = false;

    @ConfigInfo(name = "superChargedCreeper", section = "org")
    public static volatile boolean superChargedCreeper = false;

    @ConfigInfo(name = "playerDropHead", section = "org")
    public static volatile boolean playerDropHead = false;

    @ConfigInfo(name = "villagerVoidTrading", section = "org")
    public static volatile boolean villagerVoidTrading = false;

    @ConfigInfo(name = "forceRestock", section = "org")
    public static volatile boolean forceRestock = false;

    @ConfigInfo(name = "fakePlayerSpawnNoKnockback", section = "org")
    public static volatile boolean fakePlayerSpawnNoKnockback = false;

    @ConfigInfo(name = "perfectInvisibility", section = "ams")
    public static volatile boolean perfectInvisibility = false;

    @ConfigInfo(name = "sneakInvisibility", section = "ams")
    public static volatile boolean sneakInvisibility = false;

    @ConfigInfo(name = "onlyPlayerCanCreateNetherPortal", section = "ams")
    public static volatile boolean onlyPlayerCanCreateNetherPortal = false;

    @ConfigInfo(name = "itemEntityCreateNetherPortalDisabled", section = "ams")
    public static volatile boolean itemEntityCreateNetherPortalDisabled = false;

    @ConfigInfo(name = "foliageGenerateDisabled", section = "ams")
    public static volatile boolean foliageGenerateDisabled = false;

    @ConfigInfo(name = "headHunter", section = "ams")
    public static volatile boolean headHunter = false;

    @ConfigInfo(name = "setBedrockHardness")
    public static volatile float setBedrockHardness = -1.0F;

    @ConfigInfo(name = "pickaxeMinedBedrock")
    public static volatile boolean pickaxeMinedBedrock = false;

    @ConfigInfo(name = "softDeepslate", section = "org")
    public static volatile boolean softDeepslate = false;

    @ConfigInfo(name = "softObsidian", section = "org")
    public static volatile boolean softObsidian = false;

    @ConfigInfo(name = "softOres", section = "org")
    public static volatile boolean softOres = false;

    @ConfigInfo(name = "softNetherite", section = "org")
    public static volatile boolean softNetherite = false;

    @ConfigInfo(name = "riptideIgnoreConditions", section = "org")
    public static volatile boolean riptideIgnoreConditions = false;

    @ConfigInfo(name = "protectionEnchantmentCompatible", section = "org")
    public static volatile boolean protectionEnchantmentCompatible = false;

    @ConfigInfo(name = "damageEnchantmentCompatible", section = "org")
    public static volatile boolean damageEnchantmentCompatible = false;

    @ConfigInfo(name = "maxBlockPlaceDistance", section = "org")
    public static volatile double maxBlockPlaceDistance = -1.0D;

    @ConfigInfo(name = "maxBlockPlaceDistanceReferToEntity", section = "org")
    public static volatile boolean maxBlockPlaceDistanceReferToEntity = false;

    @ConfigInfo(name = "canActivatesObserver", section = "org")
    public static volatile boolean canActivatesObserver = false;

    @ConfigInfo(name = "customPiglinBarteringTime", section = "org")
    public static volatile long customPiglinBarteringTime = -1L;

    @ConfigInfo(name = "climbingBoat", section = "org")
    public static volatile boolean climbingBoat = false;

    @ConfigInfo(name = "experienceOrbMerge", section = "org")
    public static volatile boolean experienceOrbMerge = false;

    @ConfigInfo(name = "spawnBabyProbably", section = "tis")
    public static volatile double spawnBabyProbably = -1.0D;

    @ConfigInfo(name = "spawnJockeyProbably", section = "tis")
    public static volatile double spawnJockeyProbably = -1.0D;

    @ConfigInfo(name = "spawnLeaderZombieProbably", section = "tis")
    public static volatile double spawnLeaderZombieProbably = -1.0D;

    @ConfigInfo(name = "entityPlacementIgnoreCollision", section = "tis")
    public static volatile boolean entityPlacementIgnoreCollision = false;

    @ConfigInfo(name = "minecartPlaceableOnGround", section = "tis")
    public static volatile boolean minecartPlaceableOnGround = false;

    @ConfigInfo(name = "minecartTakePassengerMinVelocity", section = "tis")
    public static volatile double minecartTakePassengerMinVelocity = 0.1D;

    @ConfigInfo(name = "chainStone")
    public static volatile String chainStone = "false";

    @ConfigInfo(name = "structureBlockLimit")
    public static volatile int structureBlockLimit = 48;

    @ConfigInfo(name = "structureBlockIgnored")
    public static volatile String structureBlockIgnored = "minecraft:structure_void";

    @ConfigInfo(name = "summonNaturalLightning")
    public static volatile boolean summonNaturalLightning = false;

    @ConfigInfo(name = "fillUpdates")
    public static volatile boolean fillUpdates = true;

    @ConfigInfo(name = "renewableCoral")
    public static volatile String renewableCoral = "false";

    @ConfigInfo(name = "thickFungusGrowth")
    public static volatile String thickFungusGrowth = "false";

    @ConfigInfo(name = "optimizedTNT")
    public static volatile boolean optimizedTNT = false;

    @ConfigInfo(name = "tntRandomRange")
    public static volatile double tntRandomRange = -1.0D;

    @ConfigInfo(name = "largeEnderChest", section = "ams")
    public static volatile boolean largeEnderChest = false;

    @ConfigInfo(name = "carpetCommandPermissionLevel")
    public static volatile String carpetCommandPermissionLevel = "ops";

    @me.earthme.luminol.config.flags.DoNotLoad(when = me.earthme.luminol.enums.EnumLoadType.RELOAD)
    @ConfigInfo(name = "largeShulkerBox", section = "ams")
    public static volatile boolean largeShulkerBox = false;

    public static int effectiveEnderChestRows() {
        if (largeEnderChest) {
            return 6;
        }
        int rows = fun.bm.lophine.config.modules.function.ContainerExpansionConfig.enderchestRows;
        return rows > 0 && rows < 7 ? rows : 3;
    }

    public static int normalizedStructureBlockLimit() {
        return Math.max(48, structureBlockLimit);
    }

    public static List<net.minecraft.world.level.block.Block> structureIgnoredBlocks(final List<net.minecraft.world.level.block.Block> original) {
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(structureBlockIgnored);
        net.minecraft.world.level.block.Block block = id == null ? net.minecraft.world.level.block.Blocks.STRUCTURE_VOID
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID);
        return original.contains(block) ? original : java.util.stream.Stream.concat(original.stream(), java.util.stream.Stream.of(block)).toList();
    }

    public static boolean chainStoneEnabled() {
        return "true".equalsIgnoreCase(chainStone) || "stick_to_all".equalsIgnoreCase(chainStone);
    }

    public static double minecartPickupSpeedThreshold() {
        return minecartTakePassengerMinVelocity == 0.1D ? 0.01D : Math.nextDown(minecartTakePassengerMinVelocity);
    }

    private static final java.util.Random SPAWN_BABY_RANDOM = new java.util.Random();
    private static final java.util.Random SPAWN_JOCKEY_RANDOM = new java.util.Random();
    private static final java.util.Random SPAWN_LEADER_RANDOM = new java.util.Random();

    public static boolean spawnBabyDecision(final boolean original) {
        return spawnBabyProbably >= 0.0D ? spawnBabyProbably > 0.0D && SPAWN_BABY_RANDOM.nextFloat() <= spawnBabyProbably : original;
    }

    public static float spawnBabyChance(final float original) {
        return spawnBabyProbably >= 0.0D ? (spawnBabyDecision(false) ? 1.0F : -1.0F) : original;
    }

    public static boolean spawnJockeyDecision(final boolean original) {
        return spawnJockeyProbably >= 0.0D ? spawnJockeyProbably > 0.0D && SPAWN_JOCKEY_RANDOM.nextFloat() <= spawnJockeyProbably : original;
    }

    public static boolean spawnLeaderDecision(final boolean original) {
        return spawnLeaderZombieProbably >= 0.0D ? spawnLeaderZombieProbably > 0.0D && SPAWN_LEADER_RANDOM.nextFloat() <= spawnLeaderZombieProbably : original;
    }

    public static float spawnLeaderChance(final float original) {
        if (spawnLeaderZombieProbably >= 0.0D) {
            double chance = spawnLeaderZombieProbably;
            return chance > 0.0D && SPAWN_LEADER_RANDOM.nextFloat() <= chance ? -0.1F : Float.MAX_VALUE;
        }
        return original;
    }

    public static final class StriderJockeyDecision {
        private Boolean jockey;
        private boolean ziglin;

        public int tweak(int original) {
            if (spawnJockeyProbably >= 0.0D) {
                if (this.jockey == null) {
                    this.ziglin = SPAWN_JOCKEY_RANDOM.nextInt(4) == 0;
                    double chance = spawnJockeyProbably;
                    this.jockey = chance > 0.0D && SPAWN_JOCKEY_RANDOM.nextFloat() <= chance;
                    return this.jockey && this.ziglin ? 0 : 1;
                }
                return this.jockey && !this.ziglin ? 0 : 1;
            }
            return original;
        }
    }

    public static int striderJockeyType() {
        if (!(spawnJockeyProbably >= 0.0D)) {
            return -1;
        }
        boolean ziglin = SPAWN_JOCKEY_RANDOM.nextInt(4) == 0;
        return spawnJockeyDecision(false) ? (ziglin ? 1 : 2) : 0;
    }

    public static int striderJockeyRoll(final int original, final int type, final int wanted) {
        return type < 0 ? original : (type == wanted ? 0 : 1);
    }

    @ConfigInfo(name = "breedingCooldownDisabled", section = "tis")
    public static volatile boolean breedingCooldownDisabled = false;

    @ConfigInfo(name = "blockEventPacketRange", section = "tis")
    public static volatile double blockEventPacketRange = 64.0D;

    @ConfigInfo(name = "disableOpenOrWaterDetection", section = "org")
    public static volatile boolean disableOpenOrWaterDetection = false;

    @ConfigInfo(name = "creativeImmuneKill", section = "org")
    public static volatile boolean creativeImmuneKill = false;

    @ConfigInfo(name = "extinguishedCampfire", section = "ams")
    public static volatile boolean extinguishedCampfire = false;

    @ConfigInfo(name = "powerfulExpMending", section = "ams")
    public static volatile boolean powerfulExpMending = false;

    @ConfigInfo(name = "clientSettingsLostOnRespawnFix", section = "tis")
    public static volatile boolean clientSettingsLostOnRespawnFix = false;

    @ConfigInfo(name = "sensibleEnderman", section = "ams")
    public static volatile boolean sensibleEnderman = false;

    @ConfigInfo(name = "entityInstantDeathRemoval", section = "tis")
    public static volatile boolean entityInstantDeathRemoval = false;

    @ConfigInfo(name = "farmlandTrampledDisabled", section = "tis")
    public static volatile boolean farmlandTrampledDisabled = false;

    @ConfigInfo(name = "shulkerGolem", section = "ams")
    public static volatile boolean shulkerGolem = false;

    @ConfigInfo(name = "preventEndSpikeRespawn", section = "ams")
    public static volatile String preventEndSpikeRespawn = "false";

    @ConfigInfo(name = "yeetOutOfOrderChatKick", section = "tis")
    public static volatile boolean yeetOutOfOrderChatKick = false;

    @ConfigInfo(name = "betterCraftableBoneBlock", section = "ams")
    public static volatile boolean betterCraftableBoneBlock = false;

    @ConfigInfo(name = "betterCraftableDispenser", section = "ams")
    public static volatile boolean betterCraftableDispenser = false;

    @ConfigInfo(name = "viewDistance")
    public static volatile int viewDistance = 0;

    @ConfigInfo(name = "simulationDistance")
    public static volatile int simulationDistance = 0;

    @ConfigInfo(name = "tickCommandPermission", section = "tis")
    public static volatile String tickCommandPermission = "3";

    @ConfigInfo(name = "tickFreezeCommandToggleable", section = "tis")
    public static volatile boolean tickFreezeCommandToggleable = false;

    @ConfigInfo(name = "syncServerMsptMetricsData", section = "tis")
    public static volatile boolean syncServerMsptMetricsData = false;

    @ConfigInfo(name = "simpleInGameCalculator")
    public static volatile boolean simpleInGameCalculator = false;

    @ConfigInfo(name = "microTiming", section = "tis")
    public static volatile boolean microTiming = false;

    @ConfigInfo(name = "fastRedstoneDust")
    public static volatile boolean fastRedstoneDust = false;

    @ConfigInfo(name = "lagFreeSpawning")
    public static volatile boolean lagFreeSpawning = false;

    @ConfigInfo(name = "optimizedFastEntityMovement", section = "tis")
    public static volatile boolean optimizedFastEntityMovement = false;

    @ConfigInfo(name = "optimizedHardHitBoxEntityCollision", section = "tis")
    public static volatile boolean optimizedHardHitBoxEntityCollision = false;

    @ConfigInfo(name = "tntFuseDuration", section = "tis")
    public static volatile int tntFuseDuration = 80;

    @ConfigInfo(name = "defaultLoggers")
    public static volatile List<String> defaultLoggers = List.of();

    public static boolean mergedUpdateSuppressionCrashEnabled() {
        return !"false".equals(amsUpdateSuppressionCrashFix) || yeetUpdateSuppressionCrash;
    }

    public static boolean amsUpdateSuppressionSilent() {
        return "silence".equals(amsUpdateSuppressionCrashFix);
    }

    public static int normalizedTntFuseDuration() {
        return Math.clamp(tntFuseDuration, 0, Short.MAX_VALUE);
    }

    public static int normalizedTickCommandPermission() {
        return switch (tickCommandPermission.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "0" -> 0;
            case "1" -> 1;
            case "ops", "2" -> 2;
            case "3" -> tickCommandCarpetfied ? 2 : 3;
            default -> 4;
        };
    }

    public static int normalizedPushLimit() {
        return Math.clamp(pushLimit, 1, 1024);
    }

    public static int normalizedRailPowerLimit() {
        return Math.clamp(railPowerLimit, 1, 1024);
    }

    public static int normalizedForceloadLimit() {
        return Math.clamp(forceloadLimit, 1, 20_000_000);
    }

    public static boolean hasQuasiSignal(final net.minecraft.world.level.SignalGetter level, final net.minecraft.core.BlockPos pos) {
        for (int i = 1; i <= quasiConnectivity; i++) {
            net.minecraft.core.BlockPos above = pos.above(i);
            if (level.isOutsideBuildHeight(above)) {
                break;
            }
            if (level.hasNeighborSignal(above)) {
                return true;
            }
        }
        return false;
    }

    public static int shulkerBoxStackSize() {
        String value = stackableShulkerBoxes;
        if ("true".equalsIgnoreCase(value)) {
            return 64;
        }
        if (value == null || "false".equalsIgnoreCase(value)) {
            return 1;
        }
        try {
            int size = Integer.parseInt(value);
            return size >= 2 && size <= 64 ? size : 1;
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public static float carpetBlockDestroySpeed(
            final net.minecraft.world.level.block.Block block,
            final net.minecraft.world.level.BlockGetter level,
            final net.minecraft.core.BlockPos pos,
            final float original
    ) {
        if (block == net.minecraft.world.level.block.Blocks.BEDROCK && setBedrockHardness != -1.0F) {
            return setBedrockHardness;
        }
        if (softDeepslate) {
            if (isSoftDeepslate(block)) {
                return net.minecraft.world.level.block.Blocks.STONE.defaultDestroyTime();
            }
            if (isSoftCobbledDeepslate(block)) {
                return net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultDestroyTime();
            }
        }
        if (softObsidian && (block == net.minecraft.world.level.block.Blocks.OBSIDIAN || block == net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)) {
            return net.minecraft.world.level.block.Blocks.END_STONE.defaultDestroyTime();
        }
        if (softOres) {
            if (isSoftOre(block)) {
                return net.minecraft.world.level.block.Blocks.STONE.defaultDestroyTime();
            }
            if (isSoftDeepslateOre(block)) {
                return net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState().getDestroySpeed(level, pos);
            }
            if (block == net.minecraft.world.level.block.Blocks.NETHER_QUARTZ_ORE || block == net.minecraft.world.level.block.Blocks.NETHER_GOLD_ORE) {
                return net.minecraft.world.level.block.Blocks.NETHERRACK.defaultDestroyTime();
            }
        }
        if (softNetherite && (block == net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS || block == net.minecraft.world.level.block.Blocks.NETHERITE_BLOCK)) {
            return original / 18.0F;
        }
        return original;
    }

    public static boolean isProtectionEnchantment(final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        return key == net.minecraft.world.item.enchantment.Enchantments.PROTECTION
                || key == net.minecraft.world.item.enchantment.Enchantments.BLAST_PROTECTION
                || key == net.minecraft.world.item.enchantment.Enchantments.FIRE_PROTECTION
                || key == net.minecraft.world.item.enchantment.Enchantments.PROJECTILE_PROTECTION;
    }

    public static boolean isDamageEnchantment(final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        return key == net.minecraft.world.item.enchantment.Enchantments.SHARPNESS
                || key == net.minecraft.world.item.enchantment.Enchantments.SMITE
                || key == net.minecraft.world.item.enchantment.Enchantments.BANE_OF_ARTHROPODS
                || key == net.minecraft.world.item.enchantment.Enchantments.IMPALING
                || key == net.minecraft.world.item.enchantment.Enchantments.DENSITY
                || key == net.minecraft.world.item.enchantment.Enchantments.BREACH;
    }

    private static boolean isSoftDeepslate(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.DEEPSLATE
                || block == net.minecraft.world.level.block.Blocks.CHISELED_DEEPSLATE
                || block == net.minecraft.world.level.block.Blocks.POLISHED_DEEPSLATE
                || block == net.minecraft.world.level.block.Blocks.POLISHED_DEEPSLATE_SLAB
                || block == net.minecraft.world.level.block.Blocks.POLISHED_DEEPSLATE_STAIRS
                || block == net.minecraft.world.level.block.Blocks.POLISHED_DEEPSLATE_WALL
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICK_SLAB
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICK_STAIRS
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICK_WALL;
    }

    private static boolean isSoftCobbledDeepslate(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE
                || block == net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE_SLAB
                || block == net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE_STAIRS
                || block == net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE_WALL
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_TILES
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_TILE_SLAB
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_TILE_STAIRS
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_TILE_WALL
                || block == net.minecraft.world.level.block.Blocks.CRACKED_DEEPSLATE_BRICKS
                || block == net.minecraft.world.level.block.Blocks.CRACKED_DEEPSLATE_TILES;
    }

    private static boolean isSoftOre(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.COAL_ORE
                || block == net.minecraft.world.level.block.Blocks.IRON_ORE
                || block == net.minecraft.world.level.block.Blocks.COPPER_ORE
                || block == net.minecraft.world.level.block.Blocks.LAPIS_ORE
                || block == net.minecraft.world.level.block.Blocks.GOLD_ORE
                || block == net.minecraft.world.level.block.Blocks.REDSTONE_ORE
                || block == net.minecraft.world.level.block.Blocks.DIAMOND_ORE
                || block == net.minecraft.world.level.block.Blocks.EMERALD_ORE;
    }

    private static boolean isSoftDeepslateOre(net.minecraft.world.level.block.Block block) {
        return block == net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_COPPER_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_LAPIS_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_GOLD_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_REDSTONE_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE
                || block == net.minecraft.world.level.block.Blocks.DEEPSLATE_EMERALD_ORE;
    }

    public static boolean forceOpenChest() {
        return "any".equalsIgnoreCase(forceOpenContainer);
    }

    public static boolean forceOpenShulkerBox() {
        return forceOpenChest() || "shulker_box".equalsIgnoreCase(forceOpenContainer);
    }

    private static String appliedUltra = "false";

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void refreshTisDebugConfig() {
        ultraSecretSetting = fun.bm.lophine.carpet.TisDebugSettings.validateUltra(ultraSecretSetting, appliedUltra, null);
        appliedUltra = ultraSecretSetting;
    }

    private static String appliedScriptACE;
    private static String appliedScriptsAppStore;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void refreshScarpetConfig() {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || !server.isReady()) return;
        if (!java.util.Objects.equals(appliedScriptACE, commandScriptACE)) {
            appliedScriptACE = commandScriptACE;
            carpet.script.external.ScarpetRuntime.of(server).updateRunPermission(null);
        }
        if (!java.util.Objects.equals(appliedScriptsAppStore, scriptsAppStore)) {
            appliedScriptsAppStore = scriptsAppStore;
            String value = scriptsAppStore;
            carpet.script.external.ScarpetRuntime.of(server).submit(() -> {
                carpet.script.external.Carpet.ruleChanged(server.createCommandSourceStack(), "scriptsAppStore", value);
                return null;
            });
        }
    }

    private static String appliedMicroDye = "true";
    private static String appliedMicroTarget = "marker_only";
    private static boolean appliedMicroTiming;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void refreshMicroTiming() {
        String accepted = fun.bm.lophine.carpet.TisMicroTimingMarkers.validateDyeRule(microTimingDyeMarker, appliedMicroDye, null);
        microTimingDyeMarker = accepted == null ? appliedMicroDye : accepted;
        if (appliedMicroTiming != microTiming || !appliedMicroDye.equals(microTimingDyeMarker) || !appliedMicroTarget.equals(microTimingTarget)) {
            appliedMicroTiming = microTiming;
            appliedMicroDye = microTimingDyeMarker;
            appliedMicroTarget = microTimingTarget;
            fun.bm.lophine.carpet.TisMicroTiming.ruleChanged();
        }
    }

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void refreshAmsProtocol() {
        fun.bm.lophine.protocol.AmsNetworkProtocol.resetNetworkRuleDefaultsOnLoad();
    }

    @NeedRun(when = EnumRunnableType.BEFORE_FINAL_LOAD)
    public void sendChangesToClient() {
        Boolean previousEnderChest = appliedLargeEnderChest;
        appliedLargeEnderChest = largeEnderChest;
        if (Boolean.FALSE.equals(previousEnderChest) && largeEnderChest) {
            var server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null && server.isReady()) fun.bm.lophine.carpet.CarpetRuleObservers.enderChestEnabled(server);
        }
        fun.bm.lophine.carpet.AmsFakePlayers.refresh();
        fun.bm.lophine.carpet.CarpetDistanceRuleLifecycle.refresh();
        fun.bm.lophine.carpet.CarpetInventoryRuleLifecycle.refresh();
        List<Boolean> recipes = recipeRuleValues();
        if (!recipes.equals(appliedRecipeRules)) {
            appliedRecipeRules = recipes;
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null && server.isRunning()) {
                carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsRecipeLifecycle.changed(server));
            }
        }
        if (appliedStackableDiscounts == null || appliedStackableDiscounts != stackableDiscounts) {
            net.minecraft.world.entity.ai.gossip.GossipType.MINOR_POSITIVE.max = stackableDiscounts ? 200 : 25;
            net.minecraft.world.entity.ai.gossip.GossipType.MAJOR_POSITIVE.max = stackableDiscounts ? 100 : 20;
            net.minecraft.world.entity.ai.gossip.GossipType.MAJOR_POSITIVE.decayPerTransfer = stackableDiscounts ? 100 : 20;
            appliedStackableDiscounts = stackableDiscounts;
        }
        if (!fun.bm.lophine.carpet.CarpetRuleChanges.isChanging()) CarpetProtocalDataBase.apply();
    }
}
