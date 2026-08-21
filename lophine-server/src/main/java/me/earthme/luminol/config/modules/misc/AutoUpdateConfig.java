package me.earthme.luminol.config.modules.misc;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import me.earthme.luminol.utils.AutoUpdateHelper;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "auto_update")
public class AutoUpdateConfig {
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

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (enabled) {
            if (instance == null) {
                instance = new AutoUpdateHelper();
            }
            instance.load(false);
        }
    }

    @NeedRun(when = EnumRunnableType.ON_UNLOAD)
    public void onUnloaded() {
        if (instance != null) instance.shutdown();
    }
}
