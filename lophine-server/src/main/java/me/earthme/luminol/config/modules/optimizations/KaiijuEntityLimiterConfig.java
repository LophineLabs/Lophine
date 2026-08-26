package me.earthme.luminol.config.modules.optimizations;

import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "kaiiju_entity_limiter")
public class KaiijuEntityLimiterConfig {
    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        KaiijuEntityLimits.init();
    }
}