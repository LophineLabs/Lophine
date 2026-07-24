package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "long_command_support")
public class LongCommandSupportConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
}
