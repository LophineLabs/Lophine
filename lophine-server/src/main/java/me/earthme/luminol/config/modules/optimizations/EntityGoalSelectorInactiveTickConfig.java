package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "throttle_goal_selector_tick_in_inactive_tick")
public class EntityGoalSelectorInactiveTickConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}