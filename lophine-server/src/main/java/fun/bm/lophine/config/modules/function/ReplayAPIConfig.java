package fun.bm.lophine.config.modules.function;

import fun.bm.lophine.utils.RandomProfilePool;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import me.earthme.luminol.api.config.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "replay-api")
public class ReplayAPIConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "enable-cache")
    public static boolean enableCache = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "cache-photographer-time")
    public static int cachePhotographerTime = 3600;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "cache-photographer-size")
    public static int cachePhotographerSize = 100;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        RandomProfilePool.init();
    }
}
