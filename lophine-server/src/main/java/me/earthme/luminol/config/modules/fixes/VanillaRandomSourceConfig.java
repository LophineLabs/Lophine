package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "use_vanilla_random_source")
public class VanillaRandomSourceConfig {
    @ConfigInfo(name = "enable_for_player_entity")
    public static boolean useLegacyRandomSourceForPlayers = false;
}