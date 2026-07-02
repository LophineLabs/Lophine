package fun.bm.lophine.config.modules.experiment;

import fun.bm.lophine.enums.GlobalEntitiesCounterType;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "global_entities_counter", category = EnumConfigCategory.EXPERIMENT)
public class GlobalEntitiesCounter implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "version", comments = """
            DISABLED
            DEFAULT_SYNC: Enable global entities counter origin version with sync counter module.
            DEFAULT_ASYNC: Enable global entities counter origin version with async counter module.
            PRECISE: Enable precise mob cap calculation with incremental counting. Replaces the periodic full-scan with event-driven real-time updates.
            
            You need to set per-player-mob-spawns to false on paper-world-defaults.yml or paper-world.yml""")
    public static GlobalEntitiesCounterType type = GlobalEntitiesCounterType.DISABLED;

    @DoNotLoad
    private static boolean enabled;
    @DoNotLoad
    private static boolean async;
    @DoNotLoad
    private static boolean defaultModule;

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isAsync() {
        return async;
    }

    public static boolean isDefaultModule() {
        return defaultModule;
    }

    @Override
    public void beforeFinalLoad() {
        ConfigsInstance configsInstance = ConfigManager.getConfigs("lophine");
        try {
            Boolean enabled0 = configsInstance.getConfigOrigin("experiment.global_entities_counter.enabled");
            Boolean async0 = configsInstance.getConfigOrigin("experiment.global_entities_counter.async");
            if (enabled0 != null) {
                if (enabled0) {
                    if (async0 != null && async0) {
                        type = GlobalEntitiesCounterType.DEFAULT_ASYNC;
                    } else {
                        type = GlobalEntitiesCounterType.DEFAULT_SYNC;
                    }
                }
            }
            configsInstance.removeConfig("experiment.global_entities_counter.enabled");
            configsInstance.removeConfig("experiment.global_entities_counter.async");
        } catch (Exception ignored) {
        }
        enabled = type != GlobalEntitiesCounterType.DISABLED;
        async = type == GlobalEntitiesCounterType.DEFAULT_ASYNC;
        defaultModule = type == GlobalEntitiesCounterType.DEFAULT_SYNC || type == GlobalEntitiesCounterType.DEFAULT_ASYNC;
    }
}
