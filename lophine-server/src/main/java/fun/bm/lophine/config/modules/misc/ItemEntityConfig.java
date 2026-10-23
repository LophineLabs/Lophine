package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "item-entity")
public class ItemEntityConfig {
    @ConfigInfo(name = "follow-tick-sequence-merge")
    public static boolean followTickSequenceMerge = false;
}
