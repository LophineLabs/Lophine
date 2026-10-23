package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "force_disable_packet_limiter_of_paper")
public class PaperPacketLimiterConfig {
    @ConfigInfo(name = "force_disable")
    public static boolean forceDisable = false;
}
