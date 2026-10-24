package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;
import me.earthme.luminol.enums.EnumTripwireBehavior;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "tripwire_dupe")
public class TripwireBehaviorConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;

    @ConfigInfo(name = "behavior_mode", isWorldConfig = true)
    public static EnumTripwireBehavior behaviorMode = EnumTripwireBehavior.VANILLA21;

    public static boolean enabled() {
        return enabled(null);
    }

    public static boolean enabled(@Nullable Level level) {
        return WorldValues.get(level, enabled);
    }

    public static EnumTripwireBehavior behaviorMode() {
        return behaviorMode(null);
    }

    public static EnumTripwireBehavior behaviorMode(@Nullable Level level) {
        return WorldValues.get(level, behaviorMode);
    }
}