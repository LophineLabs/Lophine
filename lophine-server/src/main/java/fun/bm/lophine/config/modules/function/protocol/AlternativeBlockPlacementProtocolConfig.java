package fun.bm.lophine.config.modules.function.protocol;

import fun.bm.lophine.enums.EnumAlternativePlaceType;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "alternative_block_placement", directory = {"protocol"})
public class AlternativeBlockPlacementProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static EnumAlternativePlaceType alternativeBlockPlacement = EnumAlternativePlaceType.NONE;

    public static boolean needIgnoreDistance() {
        return alternativeBlockPlacement != EnumAlternativePlaceType.NONE;
    }
}

