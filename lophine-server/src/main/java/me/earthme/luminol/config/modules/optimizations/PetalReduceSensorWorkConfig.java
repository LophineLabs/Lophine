package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work")
public class PetalReduceSensorWorkConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = true;
    public static final WorldValues.Key<Boolean> ENABLED = WorldValues.key();
    @ConfigInfo(name = "delay_ticks", isWorldConfig = true)
    public static int delayTicks = 10;
    public static final WorldValues.Key<Integer> DELAY_TICKS = WorldValues.key();

    public static boolean enabled() {
        return WorldValues.get(ENABLED, enabled);
    }

    public static int delayTicks() {
        return WorldValues.get(DELAY_TICKS, delayTicks);
    }
}