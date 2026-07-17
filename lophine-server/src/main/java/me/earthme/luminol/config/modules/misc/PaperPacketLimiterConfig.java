package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "force_disable_packet_limiter_of_paper", comments =
        "Force and fully disable all packet limiters of Paper, which is used to prevent from kicking by using some quick crafting mods but \n" +
                "has negative impacts on security"
)
public class PaperPacketLimiterConfig implements IConfigModule {
    @ConfigInfo(name = "force_disable")
    public static boolean forceDisable = false;
}
