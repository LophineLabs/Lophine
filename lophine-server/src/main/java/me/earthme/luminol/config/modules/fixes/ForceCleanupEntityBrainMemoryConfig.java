package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module")
public class ForceCleanupEntityBrainMemoryConfig {
    @ConfigInfo(name = "enabled_for_entity", isWorldConfig = true)
    public static boolean enabledForEntity = false;
    public static final WorldValues.Key<Boolean> ENABLED_FOR_ENTITY = WorldValues.key();

    @ConfigInfo(name = "enabled_for_block_pos", isWorldConfig = true)
    public static boolean enabledForBlockPos = false;
    public static final WorldValues.Key<Boolean> ENABLED_FOR_BLOCK_POS = WorldValues.key();

    @ConfigInfo(name = "enabled_for_position_tracker", isWorldConfig = true)
    public static boolean enabledForPositionTracker = false;
    public static final WorldValues.Key<Boolean> ENABLED_FOR_POSITION_TRACKER = WorldValues.key();

    public static boolean enabledForEntity() {
        return WorldValues.get(ENABLED_FOR_ENTITY, enabledForEntity);
    }

    public static boolean enabledForBlockPos() {
        return WorldValues.get(ENABLED_FOR_BLOCK_POS, enabledForBlockPos);
    }

    public static boolean enabledForPositionTracker() {
        return WorldValues.get(ENABLED_FOR_POSITION_TRACKER, enabledForPositionTracker);
    }
}