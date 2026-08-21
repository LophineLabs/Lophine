package fun.bm.lophine.config.modules.function.protocol;

import fun.bm.lophine.enums.PcaPlayerEntityType;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.leavesmc.leaves.protocol.PcaSyncProtocol;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "pca", directory = {"protocol"})
public class PcaSyncProtocolConfig {
    private static boolean lastEnabled = false;

    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @ConfigInfo(name = "sync-player-entity")
    public static PcaPlayerEntityType syncPlayerEntity = PcaPlayerEntityType.OPS;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (lastEnabled != enabled) {
            PcaSyncProtocol.onConfigModify(enabled);
            lastEnabled = enabled;
        }
    }
}
