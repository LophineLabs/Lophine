package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "variable_entity_waking_up")
public class GaleVariableEntityWakeupConfig {
    @ConfigInfo(name = "entity_wakeup_duration_ratio_standard_deviation", isWorldConfig = true)
    public static final WorldValues.Key<Double> entityWakeUpDurationRatioStandardDeviation = WorldValues.key(0.2);
}
