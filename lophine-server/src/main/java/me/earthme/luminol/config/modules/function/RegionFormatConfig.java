package me.earthme.luminol.config.modules.function;

import abomination.LinearRegionFile;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.IllegalFormatConversionExceptionWithOrigin;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRegionFormat;
import me.earthme.luminol.utils.BufferedLinearRegionFileFlusher;
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
    @ConfigInfo(name = "linear_io_thread_count")
    public static int linearIoThreadCount = 6;
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_io_flush_delay_ms")
    public static int linearIoFlushDelayMs = 100;
    @HotReloadUnsupported
    @ConfigInfo(name = "blinear_io_flush_delay_ms")
    public static int blinearIoFlushDelayMs = 3000;
    @HotReloadUnsupported
    @ConfigInfo(name = "blinear_io_thread_count")
    public static int blinearIoThreadCount = 6;
    @HotReloadUnsupported
    @ConfigInfo(name = "linear_use_virtual_thread")
    public static boolean linearUseVirtualThread = true;

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

        if (regionFormat == EnumRegionFormat.LINEAR_V2) {
            checkCompressionLevel();

            LinearRegionFile.SAVE_DELAY_MS = linearIoFlushDelayMs;
            LinearRegionFile.SAVE_THREAD_MAX_COUNT = linearIoThreadCount;
            LinearRegionFile.USE_VIRTUAL_THREAD = linearUseVirtualThread;
        }

        if (regionFormat == EnumRegionFormat.B_LINEAR) {
            blinearFlusher = new BufferedLinearRegionFileFlusher(blinearIoThreadCount, 20, blinearIoFlushDelayMs);

            checkCompressionLevel();

            // we don't need to consider that it will be reloaded more than once as this config is unreloadable
            Runtime.getRuntime().addShutdownHook(new Thread(() -> blinearFlusher.shutdown()));
        }
    }

    private static void checkCompressionLevel() {
        if (RegionFormatConfig.linearCompressionLevel > 23 || RegionFormatConfig.linearCompressionLevel < 1) {
            MinecraftServer.LOGGER.error("Linear or BufferedLinear region compression level should be between 1 and 22 in config: {}", RegionFormatConfig.linearCompressionLevel);
            MinecraftServer.LOGGER.error("Falling back to compression level 1.");
            RegionFormatConfig.linearCompressionLevel = 1;
        }
    }
}