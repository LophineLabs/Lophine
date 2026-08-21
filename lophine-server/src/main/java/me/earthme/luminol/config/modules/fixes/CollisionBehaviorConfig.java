package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumCollisionBehaviorMode;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig {
    @CommandSuggestions(suggest = {"VANILLA", "BLOCK_SHAPE_VANILLA", "PAPER"})
    @ConfigInfo(name = "mode")
    public static EnumCollisionBehaviorMode behaviorMode = EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA;
}