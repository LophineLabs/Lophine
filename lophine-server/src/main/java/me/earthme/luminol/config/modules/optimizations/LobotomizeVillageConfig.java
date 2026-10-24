package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager")
public class LobotomizeVillageConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean villagerLobotomizeEnabled = false;
    @ConfigInfo(name = "check_interval", isWorldConfig = true)
    public static int villagerLobotomizeCheckInterval = 100;
    @ConfigInfo(name = "wait_until_trade_locked", isWorldConfig = true)
    public static boolean villagerLobotomizeWaitUntilTradeLocked = false;

    public static boolean villagerLobotomizeEnabled() {
        return WorldValues.get(villagerLobotomizeEnabled);
    }

    public static int villagerLobotomizeCheckInterval() {
        return WorldValues.get(villagerLobotomizeCheckInterval);
    }

    public static boolean villagerLobotomizeWaitUntilTradeLocked() {
        return WorldValues.get(villagerLobotomizeWaitUntilTradeLocked);
    }
}