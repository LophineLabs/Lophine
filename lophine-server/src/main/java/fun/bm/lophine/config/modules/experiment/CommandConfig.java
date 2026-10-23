package fun.bm.lophine.config.modules.experiment;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "command")
public class CommandConfig {
    @ConfigInfo(name = "trigger_command_enabled")
    public static boolean trigger = false;

    @ConfigInfo(name = "function_command_enabled")
    public static boolean function = false;

    @ConfigInfo(name = "scoreboard_command_enabled")
    public static boolean scoreboard = false;

    @ConfigInfo(name = "enabled", directory = {"save_all_command"})
    public static boolean saveAll = false;

    @ConfigInfo(name = "log_all_process", directory = {"save_all_command"})
    public static boolean logAllProcess = false;

    @ConfigInfo(name = "save_all_command_timeout", directory = {"save_all_command"})
    public static long saveAllTimeout = 30;
}