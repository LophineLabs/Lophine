package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumCollisionBehaviorMode;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig implements IConfigModule {
    @CommandSuggestions(suggest = {"VANILLA", "BLOCK_SHAPE_VANILLA", "PAPER"})
    @ConfigInfo(name = "mode", comments =
            """
                    Decides which collision logics will be used(Moonrise and Paper modified this for optimization but would also break some vanilla behaviours at the same time).
                    Would be useful for fixing improper behaviours of some huge redstone machines
                    Available Value:
                    VANILLA
                    BLOCK_SHAPE_VANILLA
                    PAPER""")
    public static EnumCollisionBehaviorMode behaviorMode = EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA;
}