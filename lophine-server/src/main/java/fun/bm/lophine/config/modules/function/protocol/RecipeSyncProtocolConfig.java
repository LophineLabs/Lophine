package fun.bm.lophine.config.modules.function.protocol;

import fun.bm.lophine.protocol.RecipeSyncProtocol;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "recipe-sync", directory = {"protocol"})
public class RecipeSyncProtocolConfig {
    private static boolean lastEnabled = false;

    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (lastEnabled != enabled) {
            RecipeSyncProtocol.onConfigModify(enabled);
            lastEnabled = enabled;
        }
    }
}
