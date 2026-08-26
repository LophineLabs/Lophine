package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_entity_exception_catchers")
public class DisableEntityCatchConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}