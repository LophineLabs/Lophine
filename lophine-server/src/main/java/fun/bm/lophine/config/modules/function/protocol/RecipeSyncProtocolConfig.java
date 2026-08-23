package fun.bm.lophine.config.modules.function.protocol;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.protocol.RecipeSyncProtocol;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "recipe-sync", directory = {"protocol"})
public class RecipeSyncProtocolConfig {
    private static boolean lastEnabled = false;

    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exceptions) {
        if (lastEnabled != enabled) {
            RecipeSyncProtocol.onConfigModify(enabled);
            lastEnabled = enabled;
        }
    }
}
