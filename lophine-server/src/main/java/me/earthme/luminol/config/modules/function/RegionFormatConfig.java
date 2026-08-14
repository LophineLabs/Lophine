package me.earthme.luminol.config.modules.function;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import io.anonymous.anonymous.data.BufferedLinearRegionFileFlusher;
import io.anonymous.anonymous.enums.EnumRegionFormat;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.IllegalFormatConversionExceptionWithOrigin;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "region_format")
public class RegionFormatConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "format", allowAutoReset = false)
    public static EnumRegionFormat regionFormat = EnumRegionFormat.MCA;
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_compression_level")
    public static int linearCompressionLevel = 1;
    @HotReloadUnsupported
    @ConfigInfo(name = "blinear_io_flush_delay_ms")
    public static int blinearIoFlushDelayMs = 3000;
    @HotReloadUnsupported
    @ConfigInfo(name = "blinear_io_thread_count")
    public static int blinearIoThreadCount = 6;

    @DoNotLoad
    public static BufferedLinearRegionFileFlusher blinearFlusher = null;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        if (exs != null) {
            for (Exception e : exs) {
                if (e instanceof IllegalFormatConversionExceptionWithOrigin) {
                    throw new RuntimeException("Invalid region format: " + ((IllegalFormatConversionExceptionWithOrigin) e).getOrigin().toString());
                }
            }
        }

        if (regionFormat == EnumRegionFormat.B_LINEAR) {
            blinearFlusher = new BufferedLinearRegionFileFlusher(blinearIoThreadCount, 20, blinearIoFlushDelayMs);

            checkCompressionLevel();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> blinearFlusher.shutdown()));
        }
    }

    private static void checkCompressionLevel() {
        if (RegionFormatConfig.linearCompressionLevel > 23 || RegionFormatConfig.linearCompressionLevel < 1) {
            MinecraftServer.LOGGER.error("BufferedLinear region compression level should be between 1 and 22 in config: {}", RegionFormatConfig.linearCompressionLevel);
            MinecraftServer.LOGGER.error("Falling back to compression level 1.");
            RegionFormatConfig.linearCompressionLevel = 1;
        }
    }
}