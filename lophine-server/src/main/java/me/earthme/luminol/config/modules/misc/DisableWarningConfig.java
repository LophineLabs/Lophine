package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "disable_warning")
public class DisableWarningConfig {
    @ConfigInfo(name = "disable_heightmap_warning")
    public static boolean disableHeightmapWarning = false;
    @ConfigInfo(name = "disable_offline_mode_warning")
    public static boolean disableOfflineModeWarning = false;
    @ConfigInfo(name = "disable_moved_wrongly_threshold_warning")
    public static boolean disableMovedWronglyThresholdWarning = false;
}
