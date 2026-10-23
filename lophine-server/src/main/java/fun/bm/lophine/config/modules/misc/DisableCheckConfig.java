package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "disable-check")
public class DisableCheckConfig {
    @ConfigInfo(name = "disable-op-move-check")
    public static boolean disableOpMoveCheck = false;

    @ConfigInfo(name = "disable-op-fly-check")
    public static boolean disableOpFlyCheck = false;
}
