package fun.bm.lophine.carpet.config.modules;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "fakeplayer", directory = {"carpet"})
public class FakePlayerCompatConfig {
    @ConfigInfo(name = "commandPlayer")
    public static volatile String commandPlayer = "ops";

    public static boolean playerCommandEnabled() {
        return !"false".equalsIgnoreCase(commandPlayer);
    }

    @ConfigInfo(name = "fakePlayerResident")
    public static volatile boolean fakePlayerResident = false;

    @ConfigInfo(name = "openFakePlayerInventory")
    public static volatile boolean openFakePlayerInventory = false;

    @ConfigInfo(name = "fakePlayerTicksLikeRealPlayer", section = "tis")
    public static volatile boolean fakePlayerTicksLikeRealPlayer = false;

    @ConfigInfo(name = "fakePlayerDefaultSurvivalMode", section = "ams")
    public static volatile boolean fakePlayerDefaultSurvivalMode = false;

    @ConfigInfo(name = "fakePlayerInteractLikeClient", section = "ams")
    public static volatile boolean fakePlayerInteractLikeClient = false;

    @ConfigInfo(name = "fakePlayerAutoReplaceTool")
    public static volatile boolean fakePlayerAutoReplaceTool = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishment")
    public static volatile boolean fakePlayerAutoReplenishment = false;

    @ConfigInfo(name = "fakePlayerAutoReplenishmentFormShulkerBox")
    public static volatile boolean fakePlayerAutoReplenishmentFormShulkerBox = false;

    @ConfigInfo(name = "fakePlayerAutoFish")
    public static volatile boolean fakePlayerAutoFish = false;

    @ConfigInfo(name = "fakePlayerReloadAction")
    public static volatile boolean fakePlayerReloadAction = false;

}
