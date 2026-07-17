package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "use_vanilla_random_source")
public class VanillaRandomSourceConfig implements IConfigModule {
    @ConfigInfo(name = "enable_for_player_entity", comments = "Related with RNG cracks")
    public static boolean useLegacyRandomSourceForPlayers = false;
}