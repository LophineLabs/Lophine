package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "poi_range_fixes", category = EnumConfigCategory.FIXES)
public class POIRangeFixes implements IConfigModule {
    @ConfigInfo(name = "do_not_compete_poi_if_unloaded", comments = """
            Do not compete POI if it's unloaded
            Related with https://github.com/PaperMC/Folia/issues/292 
            """)
    public static boolean doNotCompetePOIIfUnloaded = false;
}
