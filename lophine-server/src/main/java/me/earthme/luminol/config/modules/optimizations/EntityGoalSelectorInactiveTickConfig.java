package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "throttle_goal_selector_tick_in_inactive_tick")
public class EntityGoalSelectorInactiveTickConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}