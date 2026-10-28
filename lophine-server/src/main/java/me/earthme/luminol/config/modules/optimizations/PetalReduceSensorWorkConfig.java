package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work")
public class PetalReduceSensorWorkConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValue<Boolean> enabled = WorldValues.key(true);
    @ConfigInfo(name = "delay_ticks")
    public static final WorldValue<Integer> delayTicks = WorldValues.key(10);
}
