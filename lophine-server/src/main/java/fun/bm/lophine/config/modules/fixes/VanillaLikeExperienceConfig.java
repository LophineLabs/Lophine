package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "vanilla-like-experience")
public class VanillaLikeExperienceConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;

    public static boolean enabled() {
        return WorldValues.get(enabled);
    }
}
