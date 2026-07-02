package fun.bm.lophine.config.modules.function.protocol;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.carpet.CarpetCompatSync;
import fun.bm.lophine.enums.PcaPlayerEntityType;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.TransformedConfig;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.PcaSyncProtocol;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "pca", directory = {"protocol"})
public class PcaSyncProtocolConfig implements IConfigModule {
    private static boolean lastEnabled = false;

    @TransformedConfig(name = "pca-sync-protocol", directory = {"protocol"})
    @ConfigInfo(name = "enabled", comments = """
            Enable PCA sync protocol support""")
    public static boolean enabled = false;

    @TransformedConfig(name = "pca-sync-player-entity", directory = {"protocol"})
    @ConfigInfo(name = "sync-player-entity", comments = """
            Controls which player entities can be watched through the PCA sync protocol.
            NOBODY: never sync player entities
            BOT: only sync Lophine fake players
            OPS: sync fake players and allow operators to sync real players
            OPS_AND_SELF: sync fake players, operators, and a player's own entity
            EVERYONE: allow all player entities""")
    public static PcaPlayerEntityType syncPlayerEntity = PcaPlayerEntityType.OPS;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        if (lastEnabled != enabled) {
            PcaSyncProtocol.onConfigModify(enabled);
            lastEnabled = enabled;
        }
        CarpetCompatSync.apply();
    }
}
