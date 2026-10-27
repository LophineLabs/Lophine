package fun.bm.lophine.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "item-entity")
public class ItemEntityConfig {
    @ConfigInfo(name = "follow-tick-sequence-merge", isWorldConfig = true)
    public static boolean followTickSequenceMerge = false;
    public static final WorldValues.Key<Boolean> FOLLOW_TICK_SEQUENCE_MERGE = WorldValues.key();

    public static boolean followTickSequenceMerge() {
        return WorldValues.get(FOLLOW_TICK_SEQUENCE_MERGE, followTickSequenceMerge);
    }
}
