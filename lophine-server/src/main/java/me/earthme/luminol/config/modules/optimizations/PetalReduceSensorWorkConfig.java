package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work")
public class PetalReduceSensorWorkConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
    @ConfigInfo(name = "delay_ticks")
    public static int delayTicks = 10;
}