package fun.bm.lophine.config.modules.function;

import fun.bm.lophine.utils.RandomProfilePool;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "replay-api")
public class ReplayAPIConfig {
    @HotReloadUnsupported
    @ConfigInfo(name = "enable-cache")
    public static boolean enableCache = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "cache-photographer-time")
    public static int cachePhotographerTime = 3600;

    @HotReloadUnsupported
    @ConfigInfo(name = "cache-photographer-size")
    public static int cachePhotographerSize = 100;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        RandomProfilePool.init();
    }
}
