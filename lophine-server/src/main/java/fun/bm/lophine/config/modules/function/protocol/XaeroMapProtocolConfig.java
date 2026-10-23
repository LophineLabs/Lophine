package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

import java.util.Random;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "xaero-map", directory = {"protocol"})
public class XaeroMapProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "xaeroMapServerID")
    public static int xaeroMapServerID = new Random().nextInt();
}
