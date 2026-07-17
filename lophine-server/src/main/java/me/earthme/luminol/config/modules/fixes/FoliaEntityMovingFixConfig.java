package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "fix_high_velocity_issue")
public class FoliaEntityMovingFixConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments =
            """
                    A simple fix of an issue on folia\s
                    (Sometimes the entity would\s
                    have a large moment that cross the\s
                    different tick regions, and it would\s
                    make the server crashed) but sometimes it might doesn't work""")
    public static boolean enabled = false;

    @ConfigInfo(name = "warn_on_detected")
    public static boolean warnOnDetected = false;
}