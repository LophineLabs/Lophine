package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "verify_publickey_only_in_online_mode")
public class PublickeyVerifyConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}