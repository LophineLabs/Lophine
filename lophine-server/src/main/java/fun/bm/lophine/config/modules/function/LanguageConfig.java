package fun.bm.lophine.config.modules.function;

import fun.bm.lophine.utils.ServerI18nUtil;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "language")
public class LanguageConfig {
    @ConfigInfo(name = "lang")
    public static String lang = "en_us";

    @ConfigInfo(name = "full_blocking_load")
    public static boolean full_blocking_load = false;

    @ConfigInfo(name = "allow_auto_reset_comments")
    public static boolean allowAutoResetComments = true;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void reloadLanguage() {
        if (ServerI18nUtil.isInit()) {
            ServerI18nUtil.reload();
        }
    }
}