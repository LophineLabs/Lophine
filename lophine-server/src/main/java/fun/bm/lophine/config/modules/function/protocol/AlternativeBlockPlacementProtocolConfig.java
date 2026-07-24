package fun.bm.lophine.config.modules.function.protocol;

import fun.bm.lophine.enums.EnumAlternativePlaceType;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "alternative_block_placement", directory = {"protocol"})
public class AlternativeBlockPlacementProtocolConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static EnumAlternativePlaceType alternativeBlockPlacement = EnumAlternativePlaceType.NONE;

    public static boolean needIgnoreDistance() {
        return alternativeBlockPlacement != EnumAlternativePlaceType.NONE;
    }
}

