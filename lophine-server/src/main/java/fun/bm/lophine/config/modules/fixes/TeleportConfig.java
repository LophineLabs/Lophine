package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig {
    @ConfigInfo(name = "enable_delay_compensation", isWorldConfig = true)
    public static boolean enableDelayCompensation = true;
    public static final WorldValues.Key<Boolean> ENABLE_DELAY_COMPENSATION = WorldValues.key();

    @ConfigInfo(name = "max_delay_compensation_ticks", isWorldConfig = true)
    public static int maxDelayCompensationTicks = 1;
    public static final WorldValues.Key<Integer> MAX_DELAY_COMPENSATION_TICKS = WorldValues.key();

    public static boolean enableDelayCompensation() {
        return WorldValues.get(ENABLE_DELAY_COMPENSATION, enableDelayCompensation);
    }

    public static int maxDelayCompensationTicks() {
        return WorldValues.get(MAX_DELAY_COMPENSATION_TICKS, maxDelayCompensationTicks);
    }
}
