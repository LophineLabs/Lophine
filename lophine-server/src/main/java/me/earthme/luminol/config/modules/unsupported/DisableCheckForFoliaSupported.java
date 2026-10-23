package me.earthme.luminol.config.modules.unsupported;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "disable_check_for_folia_supported")
public class DisableCheckForFoliaSupported {
    @ConfigInfo(name = "disable_for_paper")
    public static boolean disableForPaper = false;

    @ConfigInfo(name = "disable_for_leaves")
    public static boolean disableForLeaves = false;
}
