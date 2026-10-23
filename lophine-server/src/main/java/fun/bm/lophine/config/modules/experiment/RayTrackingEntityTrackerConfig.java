package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "ray_tracking_entity_tracker")
public class RayTrackingEntityTrackerConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "skip_marker_armor_stands")
    public static boolean skipMarkerArmorStands = true;
    @ConfigInfo(name = "check_interval_ms")
    public static int checkIntervalMs = 10;
    @ConfigInfo(name = "tracing_distance")
    public static int tracingDistance = 48;
    @ConfigInfo(name = "hitbox_limit")
    public static int hitboxLimit = 50;
}