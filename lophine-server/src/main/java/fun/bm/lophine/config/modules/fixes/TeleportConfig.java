package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig implements IConfigModule {
    @ConfigInfo(name = "enable_delay_compensation")
    public static boolean enableDelayCompensation = true;

    @ConfigInfo(name = "max_delay_compensation_ticks")
    public static int maxDelayCompensationTicks = 1;
}
