package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_async_catchers")
public class DisableAsyncCatcherConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}