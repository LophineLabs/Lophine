package fun.bm.lophine.carpet.config.modules;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.command.bot.BotCommand;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "fakeplayer", directory = {"carpet"})
public class FakePlayerCompatConfig implements IConfigModule {
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

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        if (commandPlayer && command == null) {
            command = new BotCommand("player");
            command.register();
        }
    }

    @Override
    public void onUnloaded(CommentedFileConfig configInstance) {
        if (command != null) {
            command.unregister();
            command = null;
        }
    }
}
