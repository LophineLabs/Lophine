package fun.bm.lophine.config.modules.function.protocol;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.enums.PcaPlayerEntityType;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.PcaSyncProtocol;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "pca", directory = {"protocol"})
public class PcaSyncProtocolConfig implements IConfigModule {
    private static boolean lastEnabled = false;

    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @ConfigInfo(name = "sync-player-entity")
    public static PcaPlayerEntityType syncPlayerEntity = PcaPlayerEntityType.OPS;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        if (lastEnabled != enabled) {
            PcaSyncProtocol.onConfigModify(enabled);
            lastEnabled = enabled;
        }
    }
}
