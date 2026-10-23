package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "redstone")
public class RedStoneConfig {
    @ConfigInfo(name = "shears_rotate")
    public static boolean shears = false;
}