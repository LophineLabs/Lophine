package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager")
public class LobotomizeVillageConfig {
    @ConfigInfo(name = "enabled")
    public static final WorldValues.Key<Boolean> villagerLobotomizeEnabled = WorldValues.key(false);
    @ConfigInfo(name = "check_interval")
    public static final WorldValues.Key<Integer> villagerLobotomizeCheckInterval = WorldValues.key(100);
    @ConfigInfo(name = "wait_until_trade_locked")
    public static final WorldValues.Key<Boolean> villagerLobotomizeWaitUntilTradeLocked = WorldValues.key(false);
}
