package me.earthme.luminol.config.modules.misc;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.utils.AutoUpdateHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

@ConfigClassInfo(
        category = EnumConfigCategory.MISC,
        name = "auto_update",
        comments = """
                Checks GitHub Releases for newer version's jars on a schedule.
                Downloads are staged under auto_update/lophine and written to auto_update/core.path,
                which Hyacinthusclip can consume on the next restart.
                If target_jar_path is set, server will also try to replace that launcher jar directly."""
)
public class AutoUpdateConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = "Whether the server should check for updates automatically.")
    public static boolean enabled = false;

    @ConfigInfo(name = "check_times", comments = "List of daily check times in HH:mm, based on the server's local time zone.")
    public static List<String> checkTimes = List.of("06:00");

    @ConfigInfo(name = "allow_prerelease", comments = "Whether prerelease GitHub releases are allowed when selecting an update.")
    public static boolean allowPrerelease = false;

    @ConfigInfo(name = "target_jar_path", comments = """
            Optional launcher jar path to replace after a successful download.
            Leave this blank to keep the downloaded jar staged in auto_update/lophine
            and let Hyacinthusclip switch to it through auto_update/core.path on restart.""")
    public static String targetJarPath = "";

    @DoNotLoad
    public AutoUpdateHelper instance = null;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        if (enabled) {
            if (instance == null) {
                instance = new AutoUpdateHelper();
            }
            instance.load(false);
        }
    }

    @Override
    public void onUnloaded(CommentedFileConfig configInstance) {
        if (instance != null) instance.shutdown();
    }
}
