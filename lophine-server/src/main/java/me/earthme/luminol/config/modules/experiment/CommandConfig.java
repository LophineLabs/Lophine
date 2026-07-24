package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "command")
public class CommandConfig implements IConfigModule {
    @ConfigInfo(name = "enable_data_command")
    @HotReloadUnsupported
    public static boolean data = false;
    @ConfigInfo(name = "enable_command_block")
    public static boolean commandBlock = false;
    @ConfigInfo(name = "enable_waypoints_and_waypoint_command")
    @HotReloadUnsupported
    public static boolean waypointsAndWaypointCommand = false;
}
