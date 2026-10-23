package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_entity_exception_catchers")
public class DisableEntityCatchConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}