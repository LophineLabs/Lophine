package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "item-entity")
public class ItemEntityConfig {
    @ConfigInfo(name = "follow-tick-sequence-merge")
    public static boolean followTickSequenceMerge = false;
}
