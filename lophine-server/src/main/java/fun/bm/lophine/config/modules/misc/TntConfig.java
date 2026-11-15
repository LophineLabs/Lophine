package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "tnt")
public class TntConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "skip-delayed-calculation")
    public static final WorldValue<Boolean> skipDelayedCalculation = WorldValues.key(true);
}
