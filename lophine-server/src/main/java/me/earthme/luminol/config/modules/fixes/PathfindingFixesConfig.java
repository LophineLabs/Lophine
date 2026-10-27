package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "pathfinding_fixes")
public class PathfindingFixesConfig {
    @ConfigInfo(name = "break_down_pathfinding_when_out_of_region", isWorldConfig = true)
    public static boolean breakDownPathfindingWhenOutOfRegion = false;
    public static final WorldValues.Key<Boolean> BREAK_DOWN_PATHFINDING_WHEN_OUT_OF_REGION = WorldValues.key();
    @ConfigInfo(name = "do_not_pathfind_to_not_owned_targets", isWorldConfig = true)
    public static boolean doNotPathfindToNotOwnedTargets = false;
    public static final WorldValues.Key<Boolean> DO_NOT_PATHFIND_TO_NOT_OWNED_TARGETS = WorldValues.key();

    public static boolean breakDownPathfindingWhenOutOfRegion() {
        return WorldValues.get(BREAK_DOWN_PATHFINDING_WHEN_OUT_OF_REGION, breakDownPathfindingWhenOutOfRegion);
    }

    public static boolean doNotPathfindToNotOwnedTargets() {
        return WorldValues.get(DO_NOT_PATHFIND_TO_NOT_OWNED_TARGETS, doNotPathfindToNotOwnedTargets);
    }
}
