package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;
import net.minecraft.world.level.Level;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "item_multitask")
public class ItemMultitaskConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> enabled = WorldValues.key(false);

    public static boolean enabled() {
        return enabled.get();
    }

    public static boolean enabled(Level level) {
        return enabled.get(level);
    }
}
