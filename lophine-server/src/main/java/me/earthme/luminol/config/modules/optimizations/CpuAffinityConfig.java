package me.earthme.luminol.config.modules.optimizations;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.utils.AffinityRunnableWrapper;
import net.openhft.affinity.Affinity;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "cpu_affinity")
public class CpuAffinityConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "enabled_for_tickregion")
    public static boolean enabledForTickRegion = false;
    @HotReloadUnsupported
    @ConfigInfo(name = "enable_for_chunksystem_worker")
    public static boolean enabledForChunkSystemWorker = false;
    @HotReloadUnsupported
    @ConfigInfo(name = "enable_for_chunksystem_io")
    public static boolean enabledForChunkSystemIo = false;

    @HotReloadUnsupported
    @ConfigInfo(name = "tickregion_affinity")
    public static List<String> tickRegionAffinity = Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList();
    @HotReloadUnsupported
    @ConfigInfo(name = "chunksystem_worker_affinity")
    public static List<String> chunkSystemWorkerAffinity = Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList();
    @HotReloadUnsupported
    @ConfigInfo(name = "chunksystem_io_affinity")
    public static List<String> chunkSystemIoAffinity = Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList();

    @DoNotLoad
    private static boolean inited = false;
    @DoNotLoad
    private static final Logger LOGGER = LogUtils.getLogger();
    @DoNotLoad
    public static AffinityRunnableWrapper tickRegionRunnableWrapper;
    @DoNotLoad
    public static AffinityRunnableWrapper chunkSystemWorkerRunnableWrapper;
    @DoNotLoad
    public static AffinityRunnableWrapper chunkSystemIoRunnableWrapper;

    public static Runnable wrapForTickRegion(Runnable in) {
        return tickRegionRunnableWrapper == null ? in : tickRegionRunnableWrapper.wrap(in);
    }

    public static Runnable wrapForChunkSystemWorker(Runnable in) {
        return chunkSystemWorkerRunnableWrapper == null ? in : chunkSystemWorkerRunnableWrapper.wrap(in);
    }

    public static Runnable wrapForChunkSystemIo(Runnable in) {
        return chunkSystemIoRunnableWrapper == null ? in : chunkSystemIoRunnableWrapper.wrap(in);
    }

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        if (enabledForTickRegion) {
            tickRegionRunnableWrapper = new AffinityRunnableWrapper("tick_region", parseAffinity(tickRegionAffinity));
            LOGGER.info("Tick region thread now bound to: {}", tickRegionRunnableWrapper.getAffinity());
        }

        if (enabledForChunkSystemIo) {
            chunkSystemIoRunnableWrapper = new AffinityRunnableWrapper("chunk_system_io", parseAffinity(chunkSystemIoAffinity));
            LOGGER.info("Chunk system I/O thread now bound to: {}", chunkSystemIoRunnableWrapper.getAffinity());
        }

        if (enabledForChunkSystemWorker) {
            chunkSystemWorkerRunnableWrapper = new AffinityRunnableWrapper("chunk_system_worker", parseAffinity(chunkSystemWorkerAffinity));
            LOGGER.info("Chunk system worker thread now bound to: {}", chunkSystemIoRunnableWrapper.getAffinity());
        }

        if (!inited) {
            inited = true;
        }
    }

    private @NonNull BitSet parseAffinity(@NonNull List<String> affinity) {
        int maxAvailable = Runtime.getRuntime().availableProcessors();
        BitSet affinitySet = new BitSet(affinity.size());
        affinity.stream()
                .mapToInt(str -> {
                    try {
                        return Integer.parseInt(str);
                    } catch (NumberFormatException ignored) {
                        LOGGER.warn("Unable to parse cpu id {} to a valid number, falling back to 0.", str);
                        return 0;
                    }
                })
                .distinct()
                .filter(cpuId -> {
                    if (cpuId >= 0 && cpuId < maxAvailable) {
                        return true;
                    } else {
                        LOGGER.warn("Invalid cpu id {}, ignoring.", cpuId);
                        return false;
                    }
                })
                .forEach(affinitySet::set);
        return affinitySet;
    }
}
