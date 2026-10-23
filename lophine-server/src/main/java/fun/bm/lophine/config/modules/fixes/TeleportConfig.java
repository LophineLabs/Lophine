package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig {
    @ConfigInfo(name = "enable_delay_compensation")
    public static boolean enableDelayCompensation = true;

    @ConfigInfo(name = "max_delay_compensation_ticks")
    public static int maxDelayCompensationTicks = 1;
}
