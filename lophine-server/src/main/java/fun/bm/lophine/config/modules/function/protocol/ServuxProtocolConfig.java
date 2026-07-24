package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "servux", directory = {"protocol"})
public class ServuxProtocolConfig implements IConfigModule {
    @ConfigInfo(name = "entity-protocol", directory = {"data"})
    public static boolean entityProtocol = false;

    @ConfigInfo(name = "hud-logger-protocol")
    public static boolean hudLoggerProtocol = false;

    @ConfigInfo(name = "hud-metadata-protocol")
    public static boolean hudMetadataProtocol = false;

    @ConfigInfo(name = "hud-metadata-share-seed")
    public static boolean hudMetadataShareSeed = false;

    @ConfigInfo(name = "structure-protocol")
    public static boolean structureProtocol = false;

    @ConfigInfo(name = "hud-enabled-loggers")
    public static List<String> hudEnabledLoggers = List.of("tps", "mob_caps");

    @ConfigInfo(name = "hud-update-interval")
    public static int hudUpdateInterval = 1;

    @ConfigInfo(name = "litematics-enabled", directory = {"litematics"})
    public static boolean litematicsEnabled = false;

    @CommandSuggestions(suggest = {"-1", "2097152"})
    @ConfigInfo(name = "litematics-max-nbt-size", directory = {"litematics"})
    public static int litematicsMaxNbtSize = 2097152;

    @CommandSuggestions(suggest = {"-1", "1200"})
    @ConfigInfo(name = "litematics-print-max-delay-ticks", directory = {"litematics"})
    public static int maxDelay = 1200;
}
