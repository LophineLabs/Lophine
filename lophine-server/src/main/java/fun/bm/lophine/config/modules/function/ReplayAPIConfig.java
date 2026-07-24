package fun.bm.lophine.config.modules.function;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.utils.RandomProfilePool;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "replay-api")
public class ReplayAPIConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "enable-cache")
    public static boolean enableCache = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "cache-photographer-time")
    public static int cachePhotographerTime = 3600;

    @HotReloadUnsupported
    @ConfigInfo(name = "cache-photographer-size")
    public static int cachePhotographerSize = 100;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        RandomProfilePool.init();
    }
}
