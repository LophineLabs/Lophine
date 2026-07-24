package me.earthme.luminol.config.modules.unsupported;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "disable_check_for_folia_supported")
public class DisableCheckForFoliaSupported implements IConfigModule {
    @ConfigInfo(name = "disable_for_paper")
    public static boolean disableForPaper = false;

    @ConfigInfo(name = "disable_for_leaves")
    public static boolean disableForLeaves = false;
}
