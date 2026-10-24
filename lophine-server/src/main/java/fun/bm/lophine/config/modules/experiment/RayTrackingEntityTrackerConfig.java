package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "ray_tracking_entity_tracker")
public class RayTrackingEntityTrackerConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;
    @ConfigInfo(name = "skip_marker_armor_stands", isWorldConfig = true)
    public static boolean skipMarkerArmorStands = true;
    @ConfigInfo(name = "check_interval_ms", isWorldConfig = true)
    public static int checkIntervalMs = 10;
    @ConfigInfo(name = "tracing_distance", isWorldConfig = true)
    public static int tracingDistance = 48;
    @ConfigInfo(name = "hitbox_limit", isWorldConfig = true)
    public static int hitboxLimit = 50;

    public static boolean enabled() {
        return WorldValues.get(enabled);
    }

    public static boolean skipMarkerArmorStands() {
        return WorldValues.get(skipMarkerArmorStands);
    }

    public static int checkIntervalMs() {
        return WorldValues.get(checkIntervalMs);
    }

    public static int tracingDistance() {
        return WorldValues.get(tracingDistance);
    }

    public static int hitboxLimit() {
        return WorldValues.get(hitboxLimit);
    }
}