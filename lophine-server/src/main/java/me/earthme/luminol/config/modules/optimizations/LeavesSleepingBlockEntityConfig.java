package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lithium_sleeping_block_entity")
public class LeavesSleepingBlockEntityConfig {
    @ConfigInfo(name = "enabled")
    @HotReloadUnsupported
    public static boolean enabled = true;
}
