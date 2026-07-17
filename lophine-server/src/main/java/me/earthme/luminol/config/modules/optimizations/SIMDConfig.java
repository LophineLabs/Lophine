package me.earthme.luminol.config.modules.optimizations;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import gg.pufferfish.pufferfish.simd.SIMDDetection;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "use_simd")
public class SIMDConfig implements IConfigModule {
    @DoNotLoad
    private static final Logger LOGGER = LogUtils.getLogger();
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        if (!enabled) {
            return;
        }

        // Attempt to detect vectorization
        try {
            SIMDDetection.isEnabled = SIMDDetection.canEnable(LOGGER);
        } catch (NoClassDefFoundError | Exception ignored) {
            ignored.printStackTrace();
        }

        if (SIMDDetection.isEnabled) {
            LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
        } else {
            LOGGER.warn("SIMD operations are available for your server, but are not configured!");
            LOGGER.warn("To enable additional optimizations, add \"--add-modules=jdk.incubator.vector\" to your startup flags, BEFORE the \"-jar\".");
            LOGGER.warn("If you have already added this flag, then SIMD operations are not supported on your JVM or CPU.");
            LOGGER.warn("Debug: Java: {}, test run: {}", System.getProperty("java.version"), SIMDDetection.testRun);
        }
    }
}