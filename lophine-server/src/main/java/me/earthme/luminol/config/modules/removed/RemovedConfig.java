package me.earthme.luminol.config.modules.removed;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.REMOVED, name = "removed_config")
public class RemovedConfig {
    @ConfigInfo(name = "removed")
    public static boolean enabled = true;
}