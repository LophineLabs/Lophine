package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

import java.util.Random;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "xaero-map", directory = {"protocol"})
public class XaeroMapProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "xaeroMapServerID")
    public static int xaeroMapServerID = new Random().nextInt();
}
