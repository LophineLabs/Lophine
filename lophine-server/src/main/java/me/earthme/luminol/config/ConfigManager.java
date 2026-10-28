package me.earthme.luminol.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.carpet.CarpetConfigHooks;
import me.earthme.luminol.api.config.TransformLogic;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.TransformedConfig;
import me.earthme.luminol.commands.CommandRegister;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConfigManager {
    private static boolean initialized = false;
    private static final ConfigsInstanceBuilder builder = new ConfigsInstanceBuilder();
    private static final Map<String, ConfigsInstance> configfiles = new HashMap<>();
    private static final Map<String, WorldConfigHolder> worldHolders = new ConcurrentHashMap<>();
    private static final Collection<Runnable> runnableBeforeFinalLoad = new ConcurrentLinkedQueue<>();
    private static final Set<TransformingConfig> needTransformedConfigs = ConcurrentHashMap.newKeySet();

    /**
     * Register extra {@link ConfigsInstance}s with {@link #registerConfig} before calling this,
     * then they are included in the same preload.
     */
    public static void initConfigs() {
        WorldValues.setInstance(new ServerWorldValues()); // before config start register, make sure world values instance init
        registerConfig(builder.of("luminol", "me.earthme.luminol.config.modules"));
        registerConfig(builder.of("lophine", "fun.bm.lophine.config.modules")); // add lophine global config
        // Lophine start - add carpet config
        registerConfig(builder.of(
                new File("lophine_config"),
                "lophine_carpet",
                "lophine_carpet_config.toml",
                "lophinecarpetconfig",
                "fun.bm.lophine.carpet.config.modules",
                CarpetConfigHooks.INSTANCE,
                false
        ));
        // Lophine end - add carpet config
        preLoad();
    }

    public static void registerConfig(ConfigsInstance config) {
        configfiles.put(config.getName(), config);
    }

    public static void preLoad() {
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(() -> {
                    try {
                        config.preLoadConfig();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to preload config", e);
                    }
                }))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        acceptTransformedConfigs();
    }

    public static void loadConfigFiles() {
        runTaskBeforeFinalLoad();
        // Finalize loading
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(config::finalizeLoadConfig))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        CommandRegister.register(); // register command after config loaded to enable some command didn't depend on config files
        initialized = true;
    }

    public static void registerRunnableBeforeFinalLoad(Runnable runnable) {
        if (initialized) return;
        runnableBeforeFinalLoad.add(runnable);
    }

    public static void registerTransformedConfig(@NotNull String origin, @NotNull String target, @NotNull String originKey, @NotNull String targetKey, TransformedConfig transformedConfig) {
        if (initialized) return;
        needTransformedConfigs.add(new TransformingConfig(transformedConfig, origin, target, originKey, targetKey));
    }

    public static ConfigsInstance getConfigs(String name) {
        return configfiles.get(name);
    }

    public static ConfigsInstanceBuilder getBuilder() {
        return builder;
    }

    static void runTaskBeforeFinalLoad() {
        runnableBeforeFinalLoad.forEach(Runnable::run);
        runnableBeforeFinalLoad.clear();
    }

    public static void saveConfigs() {
        saveConfigs(true);
    }

    public static void saveConfigs(boolean sync) {
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(config::saveConfigs))
                .toArray(CompletableFuture[]::new);
        if (sync) CompletableFuture.allOf(futures).join();
        saveWorldConfigs();
    }

    public static WorldConfigHolder loadWorldConfigs(@NotNull String worldName, @NotNull File worldFolder) {
        Map<String, WorldConfigInstance> instances = new LinkedHashMap<>();
        for (ConfigsInstance config : configfiles.values()) {
            if (!config.isWorldConfig()) {
                continue;
            }
            WorldConfigInstance instance = WorldConfigInstance.load(config, worldName, worldFolder);
            if (instance != null) {
                instances.put(config.getName(), instance);
            }
        }
        WorldConfigHolder holder = new WorldConfigHolder(worldName, Map.copyOf(instances));
        WorldConfigHolder previous = worldHolders.put(worldName, holder);
        if (previous != null && previous != holder) {
            previous.close();
        }
        return holder;
    }

    public static WorldConfigHolder loadWorldConfigs(@NotNull Level level) {
        return loadWorldConfigs(level.getWorld().getName(), level.getWorld().getWorldFolder());
    }

    public static @Nullable WorldConfigHolder getWorldConfigs(String worldName) {
        return worldHolders.get(worldName);
    }

    public static void saveWorldConfigs() {
        worldHolders.values().forEach(WorldConfigHolder::save);
    }

    public static void unloadWorldConfigs(String worldName, boolean save) {
        WorldConfigHolder holder = worldHolders.remove(worldName);
        if (holder == null) {
            return;
        }
        if (save) {
            holder.save();
        }
        holder.close();
    }

    static void reloadWorldOverlays(ConfigsInstance parent) {
        if (!parent.isWorldConfig()) {
            return;
        }
        for (WorldConfigHolder holder : worldHolders.values()) {
            holder.reload(parent.getName());
        }
    }

    /**
     * Rebuild every loaded level's world-config copy from live globals plus that world's overrides.
     */
    static void refreshWorldSnapshots() {
        for (WorldConfigHolder holder : worldHolders.values()) {
            holder.rebuildSnapshot();
        }
    }

    public static void acceptTransformedConfigs() {
        Set<ConfigsInstance> toReload = new HashSet<>();
        for (TransformingConfig entry : needTransformedConfigs) {
            TransformedConfig transformedConfig = entry.info();
            ConfigsInstance origin = getConfigs(entry.origKey());
            ConfigsInstance target = getConfigs(entry.tarKey());
            if (origin == null || target == null) continue;
            CommentedFileConfig originConfig = origin.getFileInstance();
            CommentedFileConfig targetConfig = target.getFileInstance();

            final String oldConfigKeyName = entry.origPath();
            final String newConfigKeyName = entry.tarPath();
            Object oldValue = originConfig.get(oldConfigKeyName);
            if (oldValue != null) {
                boolean success = true;
                if (transformedConfig.transform()) {
                    try {
                        for (Class<? extends TransformLogic> logic : transformedConfig.transformLogic()) {
                            oldValue = logic.getDeclaredConstructor().newInstance().transform(oldValue);
                        }
                        targetConfig.set(newConfigKeyName, oldValue);
                        if (transformedConfig.transformComments()) {
                            targetConfig.setComment(newConfigKeyName, originConfig.getComment(oldConfigKeyName));
                        }
                    } catch (Exception e) {
                        success = false;
                        target.logger.error("Failed to transform removed config {}!", transformedConfig.name());
                    }
                }

                if (success) origin.removeConfig(oldConfigKeyName, transformedConfig.directory());
            }
            toReload.add(target);
            toReload.add(origin);
        }
        toReload.forEach(ConfigsInstance::saveConfigs);
        needTransformedConfigs.clear(); // free space when all done
    }

    public static void reloadComments() {
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(() -> {
                    config.reload(false, false);
                }))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }
}
