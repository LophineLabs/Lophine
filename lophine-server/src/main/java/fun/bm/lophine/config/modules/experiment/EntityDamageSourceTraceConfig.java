package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "entity_damage_source_trace")
public class EntityDamageSourceTraceConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}