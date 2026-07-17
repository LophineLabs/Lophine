package me.earthme.luminol.config.modules.unsupported;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "disable_check_for_folia_supported")
public class DisableCheckForFoliaSupported implements IConfigModule {
    @ConfigInfo(name = "disable_for_paper", comments = """
            Disable check for folia-supported for spigot/bukkit/paper plugin.
            ATTENTION: No support will be provided if you enabled this.""")
    public static boolean disableForPaper = false;

    @ConfigInfo(name = "disable_for_leaves", comments = """
            Disable check for folia-supported for leaves plugin.
            ATTENTION: No support will be provided if you enabled this.""")
    public static boolean disableForLeaves = false;
}
