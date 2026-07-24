package fun.bm.lophine.config.modules.function.protocol;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.syncmatica.SyncmaticaProtocol;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "syncmatica", directory = {"protocol"})
public class SyncmaticaProtocolConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @ConfigInfo(name = "useQuota")
    public static boolean useQuota = false;
    @ConfigInfo(name = "quota-Limit")
    public static int quotaLimit = 40000000;

    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        SyncmaticaProtocol.init(enabled);
    }
}
