package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "long_command_support")
public class LongCommandSupportConfig {
    @ConfigInfo(name = "enabled", comments = """
            Some long commands can be run through the dialog command,
            but paper has prohibited it.
            Enable this to fix this problem.""")
    public static boolean enabled = true;
}
