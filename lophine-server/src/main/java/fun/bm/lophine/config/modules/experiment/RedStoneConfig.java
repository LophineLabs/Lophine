package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

/*
 * This is a config module for redstone in experimental level
 * If we think configs from here is stable for future, we will move them to function module directory
 */
@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "redstone")
public class RedStoneConfig {
    @ConfigInfo(name = "old-block-remove-behaviour")
    public static final WorldValue<Boolean> oldBlockRemoveBehaviour = WorldValues.key(false);
}
