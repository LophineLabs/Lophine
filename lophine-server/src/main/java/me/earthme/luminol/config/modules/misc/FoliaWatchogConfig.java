package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "folia_watchdog")
public class FoliaWatchogConfig {
    @ConfigInfo(name = "tick_region_time_out_ms")
    public static int tickRegionTimeOutMs = 5000;
}