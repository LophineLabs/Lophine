package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "variable_entity_waking_up")
public class GaleVariableEntityWakeupConfig implements IConfigModule {
    @ConfigInfo(name = "entity_wakeup_duration_ratio_standard_deviation")
    public static double entityWakeUpDurationRatioStandardDeviation = 0.2;
}