package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.CommandSuggestions;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumCollisionBehaviorMode;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig {
    @CommandSuggestions(suggest = {"VANILLA", "BLOCK_SHAPE_VANILLA", "PAPER"})
    @ConfigInfo(name = "mode")
    public static EnumCollisionBehaviorMode behaviorMode = EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA;
}