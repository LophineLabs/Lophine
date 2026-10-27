package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "fix_high_velocity_issue")
public class FoliaEntityMovingFixConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;
    public static final WorldValues.Key<Boolean> ENABLED = WorldValues.key();

    @ConfigInfo(name = "warn_on_detected", isWorldConfig = true)
    public static boolean warnOnDetected = false;
    public static final WorldValues.Key<Boolean> WARN_ON_DETECTED = WorldValues.key();

    public static boolean enabled() {
        return WorldValues.get(ENABLED, enabled);
    }

    public static boolean warnOnDetected() {
        return WorldValues.get(WARN_ON_DETECTED, warnOnDetected);
    }
}