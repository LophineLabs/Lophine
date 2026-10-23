package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "command")
public class CommandConfig {
    @ConfigInfo(name = "enable_data_command")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static boolean data = false;
    @ConfigInfo(name = "enable_command_block")
    public static boolean commandBlock = false;
    @ConfigInfo(name = "enable_waypoints_and_waypoint_command")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static boolean waypointsAndWaypointCommand = false;
}
