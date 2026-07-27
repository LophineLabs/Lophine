package fun.bm.lophine.config.modules.experiment;

import fun.bm.lophine.enums.GlobalEntitiesCounterType;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "global_entities_counter", category = EnumConfigCategory.EXPERIMENT)
public class GlobalEntitiesCounter implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "version")
    public static GlobalEntitiesCounterType type = GlobalEntitiesCounterType.DISABLED;

    public static boolean isEnabled() {
        return type.isEnabled();
    }

    public static boolean isAsync() {
        return type.isAsync();
    }

    public static boolean isDefaultModule() {
        return type.isDefaultModule();
    }
}
