package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;
import me.earthme.luminol.enums.EnumTripwireBehavior;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "tripwire_dupe")
public class TripwireBehaviorConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValues.Key<Boolean> enabled = WorldValues.key(false);

    @ConfigInfo(name = "behavior_mode")
    public static final WorldValues.Key<EnumTripwireBehavior> behaviorMode = WorldValues.key(EnumTripwireBehavior.VANILLA21);
}
