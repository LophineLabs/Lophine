package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "jade", directory = {"protocol"})
public class JadeProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}
