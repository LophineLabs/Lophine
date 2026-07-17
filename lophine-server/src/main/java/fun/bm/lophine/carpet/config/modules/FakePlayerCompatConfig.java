package fun.bm.lophine.carpet.config.modules;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(
        category = EnumConfigCategory.ROOT,
        name = "fakeplayer",
        directory = {"carpet"},
        comments = """
                Carpet fakeplayer compatibility mapped onto Lophine fakeplayers.
                /player command is registered by FakeplayerConfig using the legacy NMS brigadier PlayerCommand."""
)
public class FakePlayerCompatConfig implements IConfigModule {
    @ConfigInfo(name = "commandPlayer", comments = """
            Enable /player command.(not remapped)
            If you want to enable bot command, please see lophine global config.""")
    public static boolean commandPlayer = false;

    @ConfigInfo(name = "fakePlayerResident", comments = """
            Keep fakeplayers resident across unload and restart.""")
    public static boolean fakePlayerResident = false;

    @ConfigInfo(name = "openFakePlayerInventory", comments = """
            Allow opening fakeplayer inventories.""")
    public static boolean openFakePlayerInventory = false;

    @ConfigInfo(name = "fakePlayerTicksLikeRealPlayer", comments = """
            Tick fakeplayers in the network phase to better match real player timing.""")
    public static boolean fakePlayerTicksLikeRealPlayer = false;

    @ConfigInfo(name = "fakePlayerDefaultSurvivalMode", comments = """
            Force newly created fakeplayers to start in survival instead of the server default gamemode.""")
    public static boolean fakePlayerDefaultSurvivalMode = false;

    @ConfigInfo(name = "fakePlayerInteractLikeClient", comments = """
            Make fakeplayer entity interaction follow client-side fallback behavior more closely.""")
    public static boolean fakePlayerInteractLikeClient = false;

    @ConfigInfo(name = "fakePlayerAutoReplaceTool", comments = """
            Toggle automatic tool replacement for fakeplayers.""")
    public static boolean fakePlayerAutoReplaceTool = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishment", comments = """
            Toggle automatic stack replenishment for fakeplayers.""")
    public static boolean fakePlayerAutoReplenishment = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishmentFormShulkerBox", comments = """
            Let fakeplayer replenishment pull matching items out of shulker boxes in the inventory.""")
    public static boolean fakePlayerAutoReplenishmentFormShulkerBox = false;

    @ConfigInfo(name = "fakePlayerAutoFish", comments = """
            Let fakeplayers holding a fishing rod automatically cast and reel it in.""")
    public static boolean fakePlayerAutoFish = false;

    @ConfigInfo(name = "fakePlayerReloadAction", comments = """
            Persist queued fakeplayer actions across save and reload.""")
    public static boolean fakePlayerReloadAction = false;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        // /player command is now registered by FakeplayerConfig using legacy NMS brigadier PlayerCommand
    }

    @Override
    public void onUnloaded(CommentedFileConfig configInstance) {
        // /player command unregistration handled by FakeplayerConfig
    }
}
