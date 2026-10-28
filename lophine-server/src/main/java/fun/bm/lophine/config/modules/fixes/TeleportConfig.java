package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "teleport")
public class TeleportConfig {
    @ConfigInfo(name = "enable_delay_compensation")
    public static final WorldValue<Boolean> enableDelayCompensation = WorldValues.key(true);

    @ConfigInfo(name = "max_delay_compensation_ticks")
    public static final WorldValue<Integer> maxDelayCompensationTicks = WorldValues.key(1);
}
