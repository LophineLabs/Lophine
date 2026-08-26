package fun.bm.lophine.config.modules.function.protocol;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.leavesmc.leaves.protocol.syncmatica.SyncmaticaProtocol;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "syncmatica", directory = {"protocol"})
public class SyncmaticaProtocolConfig {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "useQuota")
    public static boolean useQuota = false;
    @ConfigInfo(name = "quota-Limit")
    public static int quotaLimit = 40000000;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        SyncmaticaProtocol.init(enabled);
    }
}
