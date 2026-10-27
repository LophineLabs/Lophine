package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "ray_tracking_entity_tracker")
public class RayTrackingEntityTrackerConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValues.Key<Boolean> enabled = WorldValues.key(false);
    @ConfigInfo(name = "skip_marker_armor_stands")
    public static final WorldValues.Key<Boolean> skipMarkerArmorStands = WorldValues.key(true);
    @ConfigInfo(name = "check_interval_ms")
    public static final WorldValues.Key<Integer> checkIntervalMs = WorldValues.key(10);
    @ConfigInfo(name = "tracing_distance")
    public static final WorldValues.Key<Integer> tracingDistance = WorldValues.key(48);
    @ConfigInfo(name = "hitbox_limit")
    public static final WorldValues.Key<Integer> hitboxLimit = WorldValues.key(50);
}
