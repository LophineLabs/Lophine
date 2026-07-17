package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "disable_warning")
public class DisableWarningConfig implements IConfigModule {
    @ConfigInfo(name = "disable_heightmap_warning", comments =
            """
                    Disable heightmap-check's warning""")
    public static boolean disableHeightmapWarning = false;
    @ConfigInfo(name = "disable_offline_mode_warning", comments = "Disable offline warns popped in the log when starting the server")
    public static boolean disableOfflineModeWarning = false;
    @ConfigInfo(name = "disable_moved_wrongly_threshold_warning", comments = "Disable wrongly move warns and checks")
    public static boolean disableMovedWronglyThresholdWarning = false;
}
