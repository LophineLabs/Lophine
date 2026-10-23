package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "verify_publickey_only_in_online_mode")
public class PublickeyVerifyConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}