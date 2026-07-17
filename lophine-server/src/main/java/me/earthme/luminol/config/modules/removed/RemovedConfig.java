package me.earthme.luminol.config.modules.removed;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.REMOVED, name = "removed_config")
public class RemovedConfig implements IConfigModule {
    @ConfigInfo(name = "removed", comments =
            """
                    RemovedConfig redirect to here, no any function.""")
    public static boolean enabled = true;
}