package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "rei", directory = {"protocol"})
public class REIServerProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}
