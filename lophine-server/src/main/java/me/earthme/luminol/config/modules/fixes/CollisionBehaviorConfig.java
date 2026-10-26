package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.CommandSuggestions;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;
import me.earthme.luminol.enums.EnumCollisionBehaviorMode;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig {
    @CommandSuggestions(suggest = {"VANILLA", "BLOCK_SHAPE_VANILLA", "PAPER"})
    @ConfigInfo(name = "mode", isWorldConfig = true)
    public static EnumCollisionBehaviorMode behaviorMode = EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA;

    public static EnumCollisionBehaviorMode behaviorMode() {
        return behaviorMode(null);
    }

    public static EnumCollisionBehaviorMode behaviorMode(@Nullable Level level) {
        return WorldValues.get(level, behaviorMode);
    }
}