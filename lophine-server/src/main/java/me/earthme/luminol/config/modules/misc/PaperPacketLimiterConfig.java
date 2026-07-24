package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "force_disable_packet_limiter_of_paper")
public class PaperPacketLimiterConfig implements IConfigModule {
    @ConfigInfo(name = "force_disable")
    public static boolean forceDisable = false;
}
