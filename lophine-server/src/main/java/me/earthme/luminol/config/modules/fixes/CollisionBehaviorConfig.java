package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumCollisionBehaviorMode;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig {
    @ConfigInfo(name = "mode")
    public static final WorldValue<EnumCollisionBehaviorMode> behaviorMode = WorldValues.key(EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA);
}
