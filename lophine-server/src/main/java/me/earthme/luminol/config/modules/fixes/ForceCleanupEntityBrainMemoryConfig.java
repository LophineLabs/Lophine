package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module")
public class ForceCleanupEntityBrainMemoryConfig implements IConfigModule {
    @ConfigInfo(name = "enabled_for_entity")
    public static boolean enabledForEntity = false;

    @ConfigInfo(name = "enabled_for_block_pos")
    public static boolean enabledForBlockPos = false;

    @ConfigInfo(name = "enabled_for_position_tracker")
    public static boolean enabledForPositionTracker = false;
}