package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "allow_unsafe_teleportation")
public class UnsafeTeleportationConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}