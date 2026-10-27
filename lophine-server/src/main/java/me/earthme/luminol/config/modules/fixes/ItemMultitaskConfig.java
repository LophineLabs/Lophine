package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "item_multitask")
public class ItemMultitaskConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean enabled = false;
    public static final WorldValues.Key<Boolean> ENABLED = WorldValues.key();

    public static boolean enabled() {
        return WorldValues.get(ENABLED, enabled);
    }

    public static boolean enabled(net.minecraft.world.level.Level level) {
        return WorldValues.get(level, ENABLED, enabled);
    }
}
