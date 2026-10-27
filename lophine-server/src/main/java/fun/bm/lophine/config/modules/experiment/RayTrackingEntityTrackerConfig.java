package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "ray_tracking_entity_tracker")
public class RayTrackingEntityTrackerConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;
    public static final WorldValues.Key<Boolean> ENABLED = WorldValues.key();
    @ConfigInfo(name = "skip_marker_armor_stands", isWorldConfig = true)
    public static boolean skipMarkerArmorStands = true;
    public static final WorldValues.Key<Boolean> SKIP_MARKER_ARMOR_STANDS = WorldValues.key();
    @ConfigInfo(name = "check_interval_ms", isWorldConfig = true)
    public static int checkIntervalMs = 10;
    public static final WorldValues.Key<Integer> CHECK_INTERVAL_MS = WorldValues.key();
    @ConfigInfo(name = "tracing_distance", isWorldConfig = true)
    public static int tracingDistance = 48;
    public static final WorldValues.Key<Integer> TRACING_DISTANCE = WorldValues.key();
    @ConfigInfo(name = "hitbox_limit", isWorldConfig = true)
    public static int hitboxLimit = 50;
    public static final WorldValues.Key<Integer> HITBOX_LIMIT = WorldValues.key();

    public static boolean enabled() {
        return WorldValues.get(ENABLED, enabled);
    }

    public static boolean skipMarkerArmorStands() {
        return WorldValues.get(SKIP_MARKER_ARMOR_STANDS, skipMarkerArmorStands);
    }

    public static int checkIntervalMs() {
        return WorldValues.get(CHECK_INTERVAL_MS, checkIntervalMs);
    }

    public static int tracingDistance() {
        return WorldValues.get(TRACING_DISTANCE, tracingDistance);
    }

    public static int hitboxLimit() {
        return WorldValues.get(HITBOX_LIMIT, hitboxLimit);
    }
}