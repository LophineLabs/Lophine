package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager")
public class LobotomizeVillageConfig {
    @ConfigInfo(name = "enabled")
    public static boolean villagerLobotomizeEnabled = false;
    @ConfigInfo(name = "check_interval")
    public static int villagerLobotomizeCheckInterval = 100;
    @ConfigInfo(name = "wait_until_trade_locked")
    public static boolean villagerLobotomizeWaitUntilTradeLocked = false;
}