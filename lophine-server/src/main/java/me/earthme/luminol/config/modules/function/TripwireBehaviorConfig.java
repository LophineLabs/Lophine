package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumTripwireBehavior;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "tripwire_dupe")
public class TripwireBehaviorConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @ConfigInfo(name = "behavior_mode", comments =
            """
                    Available Value:
                    VANILLA20
                    VANILLA21
                    MIXED""")
    public static EnumTripwireBehavior behaviorMode = EnumTripwireBehavior.VANILLA21;
}