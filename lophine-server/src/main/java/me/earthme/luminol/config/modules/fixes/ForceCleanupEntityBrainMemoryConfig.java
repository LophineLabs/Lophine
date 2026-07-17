package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module", comments = "This config is a temporary fix for those incorrect owned data in the memory of each mob, for more you can see https://github.com/PaperMC/Folia/issues/203")
public class ForceCleanupEntityBrainMemoryConfig implements IConfigModule {
    @ConfigInfo(name = "enabled_for_entity", comments = "When enabled, the entity's brain will clean the memory which is typed of entity and not belong to current tickregion")
    public static boolean enabledForEntity = false;

    @ConfigInfo(name = "enabled_for_block_pos", comments = "When enabled, the entity's brain will clean the memory which is typed of block_pos and not belong to current tickregion")
    public static boolean enabledForBlockPos = false;

    @ConfigInfo(name = "enabled_for_position_tracker", comments = "When enabled, the entity's brain will clean the memory which is typed of position_tracker and not belong to current tickregion")
    public static boolean enabledForPositionTracker = false;
}