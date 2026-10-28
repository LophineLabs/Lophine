package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager")
public class LobotomizeVillageConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValue<Boolean> villagerLobotomizeEnabled = WorldValues.key(false);
    @ConfigInfo(name = "check_interval")
    public static final WorldValue<Integer> villagerLobotomizeCheckInterval = WorldValues.key(100);
    @ConfigInfo(name = "wait_until_trade_locked")
    public static final WorldValue<Boolean> villagerLobotomizeWaitUntilTradeLocked = WorldValues.key(false);
}
