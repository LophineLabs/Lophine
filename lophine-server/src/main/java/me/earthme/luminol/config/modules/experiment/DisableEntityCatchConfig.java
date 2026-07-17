package me.earthme.luminol.config.modules.experiment;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_entity_exception_catchers")
public class DisableEntityCatchConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            If this config enabled, the server will crash directly when entity ticking has some errors instead of removing the entity to keep server running.
            It could prevent entity disappearing but may cause more server crashes.
            DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!""")
    public static boolean enabled = false;
}