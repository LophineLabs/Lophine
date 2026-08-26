package fun.bm.lophine.config.modules.function;

import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.command.bot.BotCommand;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "fakeplayer")
public class FakeplayerConfig {
    @ConfigInfo(name = "enable")
    public static boolean enable = true;

    @ConfigInfo(name = "unable-fakeplayer-names")
    public static List<String> unableNames = List.of("player-name");

    @ConfigInfo(name = "limit")
    public static int limit = 10;

    @ConfigInfo(name = "prefix")
    public static String prefix = "";

    @ConfigInfo(name = "suffix")
    public static String suffix = "";

    @ConfigInfo(name = "regen-amount")
    public static double regenAmount = 0.0;

    @ConfigInfo(name = "open-action-gui")
    public static boolean canOpenActionGui = false;

    @ConfigInfo(name = "use-action")
    public static boolean canUseAction = true;

    @ConfigInfo(name = "modify-config")
    public static boolean canModifyConfig = false;

    @ConfigInfo(name = "manual-save-and-load")
    public static boolean canManualSaveAndLoad = false;

    @ConfigInfo(name = "cache-skin")
    public static boolean useSkinCache = false;

    @ConfigInfo(name = "always-send-data")
    public static boolean canSendDataAlways = true;

    @ConfigInfo(name = "skip-sleep-check")
    public static boolean canSkipSleep = false;

    @ConfigInfo(name = "spawn-phantom")
    public static boolean canSpawnPhantom = false;

    @ConfigInfo(name = "simulation-distance")
    public static int simulationDistance = -1;

    @ConfigInfo(name = "enable-locator-bar")
    public static boolean enableLocatorBar = false;

    @DoNotLoad
    private BotCommand command = null;

    public static int getSimulationDistance(ServerBot bot) {
        return simulationDistance == -1 ? bot.getBukkitEntity().getSimulationDistance() : simulationDistance;
    }

    public static ServerBot.TickType tickType() {
        return FakePlayerCompatConfig.fakePlayerTicksLikeRealPlayer
                ? ServerBot.TickType.NETWORK
                : ServerBot.TickType.ENTITY_LIST;
    }

    public static boolean checkEnabled() {
        return enable || FakePlayerCompatConfig.commandPlayer;
    }

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (enable && command == null) {
            command = new BotCommand("bot");
            command.register();
        }
    }

    @NeedRun(when = EnumRunnableType.ON_UNLOAD)
    public void onUnloaded() {
        if (command != null) {
            command.unregister();
            command = null;
        }
    }
}
