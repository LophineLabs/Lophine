package me.earthme.luminol.config.modules.function;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.LophineLogger;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import me.earthme.luminol.api.config.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "secure_seed")
public class SecureSeedConfig {
    @ConfigInfo(name = "enabled")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static boolean enabled = false;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public static void checkUseV2(CommentedFileConfig configInstance) {
        Object version = configInstance.get("function.secure_seed.version");

        if (version instanceof Integer i && i == 2) {
            LophineLogger.LOGGER.error("You are using an unsupported secure seed version: {}", i);
            LophineLogger.LOGGER.warn("If you really want to use it, please roll back to 26.2 or earlier version.");
            LophineLogger.LOGGER.warn("If you force to change it, your world may broken between old and new generated chunks.");
            throw new UnsupportedSecureSeedVersionException(i);
        }
    }

    private static class UnsupportedSecureSeedVersionException extends RuntimeException {
        public UnsupportedSecureSeedVersionException(int version) {
            super("Unsupported secure seed version: " + version);
        }
    }
}
