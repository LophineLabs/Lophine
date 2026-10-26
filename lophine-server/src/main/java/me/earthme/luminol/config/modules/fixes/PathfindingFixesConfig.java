package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "pathfinding_fixes")
public class PathfindingFixesConfig {
    @ConfigInfo(name = "break_down_pathfinding_when_out_of_region", isWorldConfig = true)
    public static boolean breakDownPathfindingWhenOutOfRegion = false;
    @ConfigInfo(name = "do_not_pathfind_to_not_owned_targets", isWorldConfig = true)
    public static boolean doNotPathfindToNotOwnedTargets = false;

    public static boolean breakDownPathfindingWhenOutOfRegion() {
        return WorldValues.get(breakDownPathfindingWhenOutOfRegion);
    }

    public static boolean doNotPathfindToNotOwnedTargets() {
        return WorldValues.get(doNotPathfindToNotOwnedTargets);
    }
}
