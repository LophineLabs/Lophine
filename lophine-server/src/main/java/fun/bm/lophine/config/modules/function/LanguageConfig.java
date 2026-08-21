package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "language")
public class LanguageConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "lang")
    public static String lang = "en_us";

    @ConfigInfo(name = "full_blocking_load")
    public static boolean full_blocking_load = false;

    @ConfigInfo(name = "allow_auto_reset_comments")
    public static boolean allowAutoResetComments = true;
}