package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "save_portal_tickets")
public class SavePortalTicketsConfig {
    @ConfigInfo(name = "do_save")
    public static boolean doSave = true;
}
