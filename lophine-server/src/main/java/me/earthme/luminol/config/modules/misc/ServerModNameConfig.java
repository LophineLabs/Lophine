package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "server_mod_name")
public class ServerModNameConfig implements IConfigModule {
    @ConfigInfo(name = "name", comments = "Decides the server mod name shown in your F3 debug screen.")
    public static String serverModName = "Lophine";

    @ConfigInfo(name = "vanilla_spoof", comments = "Ignore any plugin's modification and server mod name set in this config block, only force sending brand name of vanilla")
    public static boolean fakeVanilla = false;
}