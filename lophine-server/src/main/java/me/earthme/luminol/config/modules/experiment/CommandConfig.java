package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;

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
