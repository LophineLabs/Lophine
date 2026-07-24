package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "server_mod_name")
public class ServerModNameConfig implements IConfigModule {
    @ConfigInfo(name = "name")
    public static String serverModName = "Lophine";

    @ConfigInfo(name = "vanilla_spoof")
    public static boolean fakeVanilla = false;
}