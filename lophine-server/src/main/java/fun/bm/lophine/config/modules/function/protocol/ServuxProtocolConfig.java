package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "servux", directory = {"protocol"})
public class ServuxProtocolConfig {
    @ConfigInfo(name = "entity-protocol", directory = {"data"})
    public static boolean entityProtocol = false;

    @ConfigInfo(name = "tweaks-data-protocol", directory = {"data"})
    public static boolean tweaksDataProtocol = false;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "tweaks-permission-level", directory = {"data"})
    public static int tweaksPermissionLevel = 0;

    @ConfigInfo(name = "nbt-allow-player-inventory", directory = {"data"})
    public static boolean nbtAllowPlayerInventory = true;

    @ConfigInfo(name = "nbt-allow-player-ender-items", directory = {"data"})
    public static boolean nbtAllowPlayerEnderItems = true;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "player-inventory-permission-level", directory = {"data"})
    public static int playerInventoryPermissionLevel = 2;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "player-ender-items-permission-level", directory = {"data"})
    public static int playerEnderItemsPermissionLevel = 2;

    @ConfigInfo(name = "hud-logger-protocol")
    public static boolean hudLoggerProtocol = false;

    @ConfigInfo(name = "hud-metadata-protocol")
    public static boolean hudMetadataProtocol = false;

    @ConfigInfo(name = "hud-metadata-share-seed")
    public static boolean hudMetadataShareSeed = false;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "hud-seed-permission-level")
    public static int hudSeedPermissionLevel = 2;

    @ConfigInfo(name = "hud-share-weather-status")
    public static boolean hudShareWeatherStatus = false;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "hud-weather-permission-level")
    public static int hudWeatherPermissionLevel = 0;

    @ConfigInfo(name = "structure-protocol")
    public static boolean structureProtocol = false;

    @CommandSuggestions(suggest = {"40", "600"})
    @ConfigInfo(name = "structure-timeout")
    public static int structureTimeout = 600;

    @ConfigInfo(name = "hud-enabled-loggers")
    public static List<String> hudEnabledLoggers = List.of("tps", "mob_caps");

    @ConfigInfo(name = "hud-update-interval")
    public static int hudUpdateInterval = 1;

    @ConfigInfo(name = "litematics-enabled", directory = {"litematics"})
    public static boolean litematicsEnabled = false;

    @CommandSuggestions(suggest = {"0", "2", "4"})
    @ConfigInfo(name = "litematics-task-permission-level", directory = {"litematics"})
    public static int litematicsTaskPermissionLevel = 0;

    @CommandSuggestions(suggest = {"-1", "2097152"})
    @ConfigInfo(name = "litematics-max-nbt-size", directory = {"litematics"})
    public static int litematicsMaxNbtSize = 2097152;

    @CommandSuggestions(suggest = {"-1", "1200"})
    @ConfigInfo(name = "litematics-print-max-delay-ticks", directory = {"litematics"})
    public static int maxDelay = 1200;
}
