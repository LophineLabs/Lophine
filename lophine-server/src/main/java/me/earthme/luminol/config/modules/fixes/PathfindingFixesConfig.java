package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "pathfinding_fixes")
public class PathfindingFixesConfig {
    @ConfigInfo(name = "break_down_pathfinding_when_out_of_region")
    public static boolean breakDownPathfindingWhenOutOfRegion = false;
    @ConfigInfo(name = "do_not_pathfind_to_not_owned_targets")
    public static boolean doNotPathfindToNotOwnedTargets = false;
}
