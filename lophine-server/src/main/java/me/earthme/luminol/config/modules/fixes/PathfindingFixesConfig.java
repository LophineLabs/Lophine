package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "pathfinding_fixes")
public class PathfindingFixesConfig {
    @ConfigInfo(name = "break_down_pathfinding_when_out_of_region")
    public static final WorldValue<Boolean> breakDownPathfindingWhenOutOfRegion = WorldValues.key(false);
    @ConfigInfo(name = "do_not_pathfind_to_not_owned_targets")
    public static final WorldValue<Boolean> doNotPathfindToNotOwnedTargets = WorldValues.key(false);
}
