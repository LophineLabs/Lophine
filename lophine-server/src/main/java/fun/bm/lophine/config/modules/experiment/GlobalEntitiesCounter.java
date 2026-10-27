package fun.bm.lophine.config.modules.experiment;

import fun.bm.lophine.enums.GlobalEntitiesCounterType;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(name = "global_entities_counter", category = EnumConfigCategory.EXPERIMENT)
public class GlobalEntitiesCounter {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "version", isWorldConfig = true)
    public static GlobalEntitiesCounterType type = GlobalEntitiesCounterType.DISABLED;
    public static final WorldValues.Key<GlobalEntitiesCounterType> TYPE = WorldValues.key();

    public static GlobalEntitiesCounterType type() {
        return WorldValues.get(TYPE, type);
    }

    public static boolean isEnabled() {
        return type().isEnabled();
    }

    public static boolean isAsync() {
        return type().isAsync();
    }

    public static boolean isDefaultModule() {
        return type().isDefaultModule();
    }
}
