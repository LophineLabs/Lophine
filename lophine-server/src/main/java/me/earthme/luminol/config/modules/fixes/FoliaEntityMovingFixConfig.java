package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "fix_high_velocity_issue")
public class FoliaEntityMovingFixConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValue<Boolean> enabled = WorldValues.key(false);

    @ConfigInfo(name = "warn_on_detected")
    public static final WorldValue<Boolean> warnOnDetected = WorldValues.key(false);
}
