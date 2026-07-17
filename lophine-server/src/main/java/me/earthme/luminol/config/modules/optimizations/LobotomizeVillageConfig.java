package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lobotomize_villager", comments = "Lobotomizes the villager if it cannot move (Does not disable trading)")
public class LobotomizeVillageConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean villagerLobotomizeEnabled = false;
    @ConfigInfo(name = "check_interval", comments = "The interval in ticks to check if a villager is lobotomized ")
    public static int villagerLobotomizeCheckInterval = 100;
    @ConfigInfo(name = "wait_until_trade_locked", comments = "Wait until a villager has been traded with before lobotomizing")
    public static boolean villagerLobotomizeWaitUntilTradeLocked = false;
}