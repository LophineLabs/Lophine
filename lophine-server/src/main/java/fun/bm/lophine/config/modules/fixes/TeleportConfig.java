package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig {
    @ConfigInfo(name = "enable_delay_compensation", isWorldConfig = true)
    public static boolean enableDelayCompensation = true;

    @ConfigInfo(name = "max_delay_compensation_ticks", isWorldConfig = true)
    public static int maxDelayCompensationTicks = 1;

    public static boolean enableDelayCompensation() {
        return WorldValues.get(enableDelayCompensation);
    }

    public static int maxDelayCompensationTicks() {
        return WorldValues.get(maxDelayCompensationTicks);
    }
}
