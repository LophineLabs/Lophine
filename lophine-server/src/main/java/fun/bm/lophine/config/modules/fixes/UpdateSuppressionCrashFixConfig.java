package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "update-suppression-crash-fix")
public class UpdateSuppressionCrashFixConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
}
