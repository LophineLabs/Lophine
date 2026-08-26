package me.earthme.luminol.config.modules.removed;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.TransformedConfig;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.REMOVED, name = "removed_config")
public class RemovedConfig {
    @ConfigInfo(name = "removed")
    @TransformedConfig(name = "linear_io_thread_count", directory = {"function", "region_format"})
    @TransformedConfig(name = "linear_io_flush_delay_ms", directory = {"function", "region_format"})
    @TransformedConfig(name = "linear_use_virtual_thread", directory = {"function", "region_format"})
    public static boolean enabled = true;
}