package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "appleskin", directory = {"protocol"})
public class AppleSkinProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "sync-tick-interval")
    public static int syncTickInterval = 20;
}
