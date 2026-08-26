package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "redstone")
public class RedStoneConfig {
    @ConfigInfo(name = "shears_rotate")
    public static boolean shears = false;
}