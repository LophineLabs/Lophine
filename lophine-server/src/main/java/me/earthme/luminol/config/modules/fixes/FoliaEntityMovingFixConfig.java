package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "fix_high_velocity_issue")
public class FoliaEntityMovingFixConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValues.Key<Boolean> enabled = WorldValues.key(false);

    @ConfigInfo(name = "warn_on_detected")
    public static final WorldValues.Key<Boolean> warnOnDetected = WorldValues.key(false);
}
