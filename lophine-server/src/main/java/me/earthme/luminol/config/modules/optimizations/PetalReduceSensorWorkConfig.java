package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work")
public class PetalReduceSensorWorkConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = true;
    @ConfigInfo(name = "delay_ticks", isWorldConfig = true)
    public static int delayTicks = 10;

    public static boolean enabled() {
        return WorldValues.get(enabled);
    }

    public static int delayTicks() {
        return WorldValues.get(delayTicks);
    }
}