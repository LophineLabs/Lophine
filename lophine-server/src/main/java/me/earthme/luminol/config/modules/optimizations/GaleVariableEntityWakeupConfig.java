package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "variable_entity_waking_up")
public class GaleVariableEntityWakeupConfig {
    @ConfigInfo(name = "entity_wakeup_duration_ratio_standard_deviation", isWorldConfig = true)
    public static double entityWakeUpDurationRatioStandardDeviation = 0.2;
    public static final WorldValues.Key<Double> ENTITY_WAKE_UP_DURATION_RATIO_STANDARD_DEVIATION = WorldValues.key();

    public static double entityWakeUpDurationRatioStandardDeviation() {
        return WorldValues.get(ENTITY_WAKE_UP_DURATION_RATIO_STANDARD_DEVIATION, entityWakeUpDurationRatioStandardDeviation);
    }
}