package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "vanilla-like-experience")
public class VanillaLikeExperienceConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}
