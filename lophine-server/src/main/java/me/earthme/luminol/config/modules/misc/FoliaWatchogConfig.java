package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "folia_watchdog")
public class FoliaWatchogConfig implements IConfigModule {
    @ConfigInfo(name = "tick_region_time_out_ms")
    public static int tickRegionTimeOutMs = 5000;
}