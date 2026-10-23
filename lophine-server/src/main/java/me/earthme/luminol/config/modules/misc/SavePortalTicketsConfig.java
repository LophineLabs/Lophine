package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "save_portal_tickets")
public class SavePortalTicketsConfig {
    @ConfigInfo(name = "do_save")
    public static boolean doSave = true;
}
