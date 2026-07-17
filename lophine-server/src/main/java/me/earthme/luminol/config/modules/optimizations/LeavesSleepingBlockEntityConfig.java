package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lithium_sleeping_block_entity")
public class LeavesSleepingBlockEntityConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Use sleeping blocking optimizations from lithium,\s
             on luminol the hopper optimizations of paper were totally removed and replaced by those of lithium\s
            and it's turned on by default""")
    @HotReloadUnsupported
    public static boolean enabled = true;
}
