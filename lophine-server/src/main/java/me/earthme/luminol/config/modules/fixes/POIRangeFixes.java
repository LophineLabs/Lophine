package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(name = "poi_range_fixes", category = EnumConfigCategory.FIXES)
public class POIRangeFixes {
    @ConfigInfo(name = "do_not_compete_poi_if_unloaded")
    public static final WorldValue<Boolean> doNotCompetePOIIfUnloaded = WorldValues.key(false);
}
