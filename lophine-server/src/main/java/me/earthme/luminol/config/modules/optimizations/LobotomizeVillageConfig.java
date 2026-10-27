package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager")
public class LobotomizeVillageConfig {
    @ConfigInfo(name = "enabled", isWorldConfig = true)
    public static boolean villagerLobotomizeEnabled = false;
    public static final WorldValues.Key<Boolean> VILLAGER_LOBOTOMIZE_ENABLED = WorldValues.key();
    @ConfigInfo(name = "check_interval", isWorldConfig = true)
    public static int villagerLobotomizeCheckInterval = 100;
    public static final WorldValues.Key<Integer> VILLAGER_LOBOTOMIZE_CHECK_INTERVAL = WorldValues.key();
    @ConfigInfo(name = "wait_until_trade_locked", isWorldConfig = true)
    public static boolean villagerLobotomizeWaitUntilTradeLocked = false;
    public static final WorldValues.Key<Boolean> VILLAGER_LOBOTOMIZE_WAIT_UNTIL_TRADE_LOCKED = WorldValues.key();

    public static boolean villagerLobotomizeEnabled() {
        return WorldValues.get(VILLAGER_LOBOTOMIZE_ENABLED, villagerLobotomizeEnabled);
    }

    public static int villagerLobotomizeCheckInterval() {
        return WorldValues.get(VILLAGER_LOBOTOMIZE_CHECK_INTERVAL, villagerLobotomizeCheckInterval);
    }

    public static boolean villagerLobotomizeWaitUntilTradeLocked() {
        return WorldValues.get(VILLAGER_LOBOTOMIZE_WAIT_UNTIL_TRADE_LOCKED, villagerLobotomizeWaitUntilTradeLocked);
    }
}