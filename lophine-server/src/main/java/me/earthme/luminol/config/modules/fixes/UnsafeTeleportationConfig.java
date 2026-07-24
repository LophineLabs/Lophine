package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "allow_unsafe_teleportation")
public class UnsafeTeleportationConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}