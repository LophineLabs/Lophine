package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "entity_damage_source_trace")
public class EntityDamageSourceTraceConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}