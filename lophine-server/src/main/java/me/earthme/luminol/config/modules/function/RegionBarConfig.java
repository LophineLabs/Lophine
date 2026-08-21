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

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "regionbar")
public class RegionBarConfig {
    @ConfigInfo(name = "enabled")
    public static boolean regionbarEnabled = false;
    @ConfigInfo(name = "format")
    public static String regionBarFormat = "<gray>Util<yellow>:</yellow> <util> Chunks<yellow>:</yellow> <green><chunks></green> Players<yellow>:</yellow> <green><players></green> Entities<yellow>:</yellow> <green><entities></green>";
    @ConfigInfo(name = "bar_color_list")
    public static List<BossBar.Color> barColors = List.of(BossBar.Color.GREEN, BossBar.Color.YELLOW, BossBar.Color.RED, BossBar.Color.PURPLE);
    @ConfigInfo(name = "util_color_list")
    public static List<String> utilColors = List.of("<gradient:#55ff55:#00aa00><text></gradient>", "<gradient:#ffff55:#ffaa00><text></gradient>", "<gradient:#ff5555:#aa0000><text></gradient>", "<gradient:#55ff55:#00aa00><text></gradient>");
    @ConfigInfo(name = "update_interval_ticks")
    public static int updateInterval = 15;
    @ConfigInfo(name = "display")
    public static EnumStatusBarDisplay display = EnumStatusBarDisplay.BOSS_BAR;

    @DoNotLoad
    private static boolean inited = false;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        TickableStatusBarList.raiseGlobalReload(EnumBarType.REGION);

        if (!inited) { // command has moved to CommandRegister
            inited = true;
        }
    }

    @NeedRun(when = EnumRunnableType.ON_UNLOAD)
    public void onUnloaded() {
        Bukkit.getCommandMap().getKnownCommands().remove("luminol:regionbar");
    }
}