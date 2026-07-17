package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "save_portal_tickets")
public class SavePortalTicketsConfig implements IConfigModule {
    @ConfigInfo(name = "do_save", comments = "whether or not to save the portal tickets when server stopping," +
            " this would make it acts like mc before 1.21.5," +
            " and won't auto active the portal chunk loader when server started again.")
    public static boolean doSave = true;
}
