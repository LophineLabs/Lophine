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

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "auto_update")
public class AutoUpdateConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;

    @ConfigInfo(name = "check_times")
    public static List<String> checkTimes = List.of("06:00");

    @ConfigInfo(name = "allow_prerelease")
    public static boolean allowPrerelease = false;

    @ConfigInfo(name = "target_jar_path")
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
