package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "server_mod_name")
public class ServerModNameConfig {
    @ConfigInfo(name = "name")
    public static String serverModName = "Lophine";

    @ConfigInfo(name = "vanilla_spoof")
    public static boolean fakeVanilla = false;
}