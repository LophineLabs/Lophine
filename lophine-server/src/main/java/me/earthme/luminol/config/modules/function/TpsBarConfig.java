package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumBarType;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import me.earthme.luminol.enums.EnumStatusBarDisplay;
import me.earthme.luminol.functions.bars.TickableStatusBarList;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "tpsbar")
public class TpsBarConfig {
    @ConfigInfo(name = "enabled")
    public static boolean tpsbarEnabled = false;
    @ConfigInfo(name = "format")
    public static String tpsBarFormat = "<gray>TPS<yellow>:</yellow> <tps> MSPT<yellow>:</yellow> <mspt> Ping<yellow>:</yellow> <ping>ms ChunkHot<yellow>:</yellow> <chunkhot>";
    @ConfigInfo(name = "bar_color_list")
    public static List<BossBar.Color> barColors = List.of(BossBar.Color.GREEN, BossBar.Color.YELLOW, BossBar.Color.RED, BossBar.Color.PURPLE);
    @ConfigInfo(name = "tps_color_list")
    public static List<String> tpsColors = List.of("<gradient:#55ff55:#00aa00><text></gradient>", "<gradient:#ffff55:#ffaa00><text></gradient>", "<gradient:#ff5555:#aa0000><text></gradient>", "<gradient:#55ff55:#00aa00><text></gradient>");
    @ConfigInfo(name = "ping_color_list")
    public static List<String> pingColors = List.of("<gradient:#55ff55:#00aa00><text></gradient>", "<gradient:#ffff55:#ffaa00><text></gradient>", "<gradient:#ff5555:#aa0000><text></gradient>", "<gradient:#55ff55:#00aa00><text></gradient>");
    @ConfigInfo(name = "chunkhot_color_list")
    public static List<String> chunkHotColors = List.of("<gradient:#55ff55:#00aa00><text></gradient>", "<gradient:#ffff55:#ffaa00><text></gradient>", "<gradient:#ff5555:#aa0000><text></gradient>", "<gradient:#55ff55:#00aa00><text></gradient>");
    @ConfigInfo(name = "update_interval_ticks")
    public static int updateInterval = 15;
    @ConfigInfo(name = "precision_of_tps_value")
    public static int precisionOfTPS = 2;
    @ConfigInfo(name = "precision_of_mspt_value")
    public static int precisionOfMSPT = 2;
    @ConfigInfo(name = "display")
    public static EnumStatusBarDisplay display = EnumStatusBarDisplay.BOSS_BAR;

    @DoNotLoad
    private static boolean inited = false;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        TickableStatusBarList.raiseGlobalReload(EnumBarType.TPS);

        if (!inited) { // command has moved to CommandRegister
            inited = true;
        }
    }

    @NeedRun(when = EnumRunnableType.ON_UNLOAD)
    public void onUnloaded() {
        Bukkit.getCommandMap().getKnownCommands().remove("luminol:tpsbar");
    }
}