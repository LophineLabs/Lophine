package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "throttle_goal_selector_tick_in_inactive_tick")
public class EntityGoalSelectorInactiveTickConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;
    public static final WorldValues.Key<Boolean> ENABLED = WorldValues.key();

    public static boolean enabled() {
        return WorldValues.get(ENABLED, enabled);
    }
}