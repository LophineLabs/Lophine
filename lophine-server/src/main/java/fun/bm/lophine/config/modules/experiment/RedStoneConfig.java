package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

/*
 * This is a config module for redstone in experimental level
 * If we think configs from here is stable for future, we will move them to function module directory
 */
@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "redstone")
public class RedStoneConfig {
    @ConfigInfo(name = "old-block-remove-behaviour", isWorldConfig = true)
    public static boolean oldBlockRemoveBehaviour = false;

    public static boolean oldBlockRemoveBehaviour() {
        return WorldValues.get(oldBlockRemoveBehaviour);
    }
}
