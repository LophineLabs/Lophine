package me.earthme.luminol.config.modules.optimizations;

import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "kaiiju_entity_limiter")
public class KaiijuEntityLimiterConfig {
    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        KaiijuEntityLimits.init();
    }
}