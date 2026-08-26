package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

/*
 * This is a config module for redstone in experimental level
 * If we think configs from here is stable for future, we will move them to function module directory
 */
@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "redstone")
public class RedStoneConfig {
    @ConfigInfo(name = "old-block-remove-behaviour")
    public static boolean oldBlockRemoveBehaviour = false;
}
