package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "item-entity")
public class ItemEntityConfig {
    @ConfigInfo(name = "follow-tick-sequence-merge")
    public static final WorldValue<Boolean> followTickSequenceMerge = WorldValues.key(false);
}
