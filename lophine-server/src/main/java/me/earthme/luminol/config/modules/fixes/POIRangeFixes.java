package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(name = "poi_range_fixes", category = EnumConfigCategory.FIXES)
public class POIRangeFixes {
    @ConfigInfo(name = "do_not_compete_poi_if_unloaded", isWorldConfig = true)
    public static boolean doNotCompetePOIIfUnloaded = false;

    public static boolean doNotCompetePOIIfUnloaded() {
        return WorldValues.get(doNotCompetePOIIfUnloaded);
    }
}
