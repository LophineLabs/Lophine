package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module")
public class ForceCleanupEntityBrainMemoryConfig {
    @ConfigInfo(name = "enabled_for_entity")
    public static boolean enabledForEntity = false;

    @ConfigInfo(name = "enabled_for_block_pos")
    public static boolean enabledForBlockPos = false;

    @ConfigInfo(name = "enabled_for_position_tracker")
    public static boolean enabledForPositionTracker = false;
}