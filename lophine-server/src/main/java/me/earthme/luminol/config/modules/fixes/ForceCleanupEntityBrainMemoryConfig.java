package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module")
public class ForceCleanupEntityBrainMemoryConfig {
    @ConfigInfo(name = "enabled_for_entity", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> enabledForEntity = WorldValues.key(false);

    @ConfigInfo(name = "enabled_for_block_pos", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> enabledForBlockPos = WorldValues.key(false);

    @ConfigInfo(name = "enabled_for_position_tracker", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> enabledForPositionTracker = WorldValues.key(false);

    public static boolean enabledForEntity() {
        return enabledForEntity.get();
    }

    public static boolean enabledForBlockPos() {
        return enabledForBlockPos.get();
    }

    public static boolean enabledForPositionTracker() {
        return enabledForPositionTracker.get();
    }
}
