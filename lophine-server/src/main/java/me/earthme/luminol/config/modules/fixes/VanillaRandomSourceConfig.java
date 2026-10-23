package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "use_vanilla_random_source")
public class VanillaRandomSourceConfig {
    @ConfigInfo(name = "enable_for_player_entity")
    public static boolean useLegacyRandomSourceForPlayers = false;
}