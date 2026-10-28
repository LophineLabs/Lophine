package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lithium_sleeping_block_entity")
public class LeavesSleepingBlockEntityConfig {
    @ConfigInfo(name = "enabled")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Boolean> enabled = WorldValues.key(true);
}
