package fun.bm.lophine.carpet.config.modules;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.leavesmc.leaves.command.bot.BotCommand;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "fakeplayer", directory = {"carpet"})
public class FakePlayerCompatConfig {
    @ConfigInfo(name = "commandPlayer")
    public static boolean commandPlayer = false;

    @ConfigInfo(name = "fakePlayerResident")
    public static boolean fakePlayerResident = false;

    @ConfigInfo(name = "openFakePlayerInventory")
    public static boolean openFakePlayerInventory = false;

    @ConfigInfo(name = "fakePlayerTicksLikeRealPlayer")
    public static boolean fakePlayerTicksLikeRealPlayer = false;

    @ConfigInfo(name = "fakePlayerDefaultSurvivalMode")
    public static boolean fakePlayerDefaultSurvivalMode = false;

    @ConfigInfo(name = "fakePlayerInteractLikeClient")
    public static boolean fakePlayerInteractLikeClient = false;

    @ConfigInfo(name = "fakePlayerAutoReplaceTool")
    public static boolean fakePlayerAutoReplaceTool = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishment")
    public static boolean fakePlayerAutoReplenishment = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishmentFormShulkerBox")
    public static boolean fakePlayerAutoReplenishmentFormShulkerBox = false;

    @ConfigInfo(name = "fakePlayerAutoFish")
    public static boolean fakePlayerAutoFish = false;

    @ConfigInfo(name = "fakePlayerReloadAction")
    public static boolean fakePlayerReloadAction = false;

    @DoNotLoad
    private BotCommand command = null;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (commandPlayer && command == null) {
            command = new BotCommand("player");
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
