package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "allow_unsafe_teleportation")
public class UnsafeTeleportationConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Allow non player entities enter end portals if enabled.
            If you want to use sand duping,please turn on this.
            Warning: This would cause some unsafe issues, you could learn more on : https://github.com/PaperMC/Folia/issues/297""")
    public static boolean enabled = false;
}