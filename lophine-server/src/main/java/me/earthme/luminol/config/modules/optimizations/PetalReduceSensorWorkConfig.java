package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work", comments = "When it is enabled, it will delete the line of sight cache less often and use a faster nearby comparison.")
public class PetalReduceSensorWorkConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
    @ConfigInfo(name = "delay_ticks", comments = "The interval of each entity to drop the cache(in ticks)")
    public static int delayTicks = 10;
}