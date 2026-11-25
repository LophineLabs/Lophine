package me.earthme.luminol.config.modules.optimizations;

import com.mojang.logging.LogUtils;
import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import me.earthme.luminol.api.config.enums.EnumRunnableType;
import me.earthme.luminol.utils.AffinityRunnableWrapper;
import net.openhft.affinity.Affinity;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.BitSet;
import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "cpu_affinity")
public class CpuAffinityConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "enabled_for_tickregion")
    public static final WorldValue<Boolean> enabledForTickRegion = WorldValues.key(false);
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "enable_for_chunksystem_worker")
    public static final WorldValue<Boolean> enabledForChunkSystemWorker = WorldValues.key(false);
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "enable_for_chunksystem_io")
    public static final WorldValue<Boolean> enabledForChunkSystemIo = WorldValues.key(false);
    @ConfigInfo(name = "enable_for_netty_io")
    public static final WorldValue<Boolean> enableForNettyIo = WorldValues.key(false);

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "tickregion_affinity")
    public static final WorldValue<List<String>> tickRegionAffinity = WorldValues.key(Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList());
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "chunksystem_worker_affinity")
    public static final WorldValue<List<String>> chunkSystemWorkerAffinity = WorldValues.key(Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList());
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "chunksystem_io_affinity")
    public static final WorldValue<List<String>> chunkSystemIoAffinity = WorldValues.key(Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList());
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "netty_io_affinity")
    public static final WorldValue<List<String>> nettyIoAffinity = WorldValues.key(Affinity.getAffinity()
            .stream()
            .mapToObj(String::valueOf)
            .toList());

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
    @DoNotLoad
    public static AffinityRunnableWrapper nettyIoRunnableWrapper;

    public static Runnable wrapForTickRegion(Runnable in) {
        return tickRegionRunnableWrapper == null ? in : tickRegionRunnableWrapper.wrap(in);
    }

    public static Runnable wrapForChunkSystemWorker(Runnable in) {
        return chunkSystemWorkerRunnableWrapper == null ? in : chunkSystemWorkerRunnableWrapper.wrap(in);
    }

    public static Runnable wrapForChunkSystemIo(Runnable in) {
        return chunkSystemIoRunnableWrapper == null ? in : chunkSystemIoRunnableWrapper.wrap(in);
    }

    public static Runnable wrapForNettyIo(Runnable in) {
        return nettyIoRunnableWrapper == null ? in : nettyIoRunnableWrapper.wrap(in);
    }

    @NeedRun(when = EnumRunnableType.BEFORE_SERVER_INIT)
    public void onLoaded() {
        if (inited) {
            return;
        }
        inited = true;

        if (enabledForTickRegion.get()) {
            tickRegionRunnableWrapper = new AffinityRunnableWrapper("tick_region", parseAffinity("tick_region", tickRegionAffinity.get()));
            LOGGER.info("Tick region thread now bound to: {}", tickRegionRunnableWrapper.affinity());
        }

        if (enabledForChunkSystemIo.get()) {
            chunkSystemIoRunnableWrapper = new AffinityRunnableWrapper("chunk_system_io", parseAffinity("chunk_system_io", chunkSystemIoAffinity.get()));
            LOGGER.info("Chunk system I/O thread now bound to: {}", chunkSystemIoRunnableWrapper.affinity());
        }

        if (enabledForChunkSystemWorker.get()) {
            chunkSystemWorkerRunnableWrapper = new AffinityRunnableWrapper("chunk_system_worker", parseAffinity("chunk_system_worker", chunkSystemWorkerAffinity.get()));
            LOGGER.info("Chunk system worker thread now bound to: {}", chunkSystemWorkerRunnableWrapper.affinity());
        }

        if (enableForNettyIo.get()) {
            nettyIoRunnableWrapper = new AffinityRunnableWrapper("netty_io", parseAffinity("netty_io", nettyIoAffinity.get()));
            LOGGER.info("Netty I/O thread now bound to: {}", nettyIoRunnableWrapper.affinity());
        }
    }

    private @NonNull BitSet parseAffinity(@NonNull String name, @NonNull List<String> affinity) {
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
        if (affinitySet.isEmpty() && !affinity.isEmpty()) {
            LOGGER.warn("All configured cpu ids for {} were filtered out (available processors = {}), the affinity mask is empty and binding will be skipped for these threads.", name, maxAvailable);
        }
        return affinitySet;
    }
}
