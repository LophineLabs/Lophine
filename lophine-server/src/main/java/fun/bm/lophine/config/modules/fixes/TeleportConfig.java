package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig {
    @ConfigInfo(name = "enable_delay_compensation", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> enableDelayCompensation = WorldValues.key(true);

    @ConfigInfo(name = "max_delay_compensation_ticks", isWorldConfig = true)
    public static final WorldValues.Key<Integer> maxDelayCompensationTicks = WorldValues.key(1);

    public static boolean enableDelayCompensation() {
        return enableDelayCompensation.get();
    }

    public static int maxDelayCompensationTicks() {
        return maxDelayCompensationTicks.get();
    }
}
