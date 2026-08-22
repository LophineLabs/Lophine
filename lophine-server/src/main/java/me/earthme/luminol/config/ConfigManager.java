package me.earthme.luminol.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.commands.CommandRegister;
import me.earthme.luminol.config.flags.TransformedConfig;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConfigManager {
    private static boolean initialized = false;
    private static final ConfigsInstanceBuilder builder = new ConfigsInstanceBuilder();
    private static final Map<String, ConfigsInstance> configfiles = new HashMap<>();
    private static final Collection<Runnable> runnableBeforeFinalLoad = new ConcurrentLinkedQueue<>();
    private static final Map<TransformedConfig, String[]> needTransformedConfigs = new ConcurrentHashMap<>();
    // String[]:
    // 0 -> origin key
    // 1 -> target key
    // 2 -> origin full path
    // 3 -> target full path

    public static void initConfigs() {
        registerConfig(builder.of("luminol", "me.earthme.luminol.config.modules"));
        registerConfig(builder.of("lophine", "fun.bm.lophine.config.modules")); // add lophine global config
        // Lophine start - add carpet config
        registerConfig(builder.of(
                new java.io.File("lophine_config"),
                "lophine_carpet",
                "lophine_carpet_config.toml",
                "lophinecarpetconfig",
                "fun.bm.lophine.carpet.config.modules"
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
        needTransformedConfigs.put(transformedConfig, new String[]{origin, target, originKey, targetKey});
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

    public static void reApplyStagedConfigs() {
        CompletableFuture<?>[] futures = configfiles.values().stream()
                .map(config -> CompletableFuture.runAsync(config::reApplyStagedConfigs))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    public static void saveConfigs() {
        saveConfigs(true);
    }

    public static void saveConfigs(boolean async) {
        if (async) {
            CompletableFuture<?>[] futures = configfiles.values().stream()
                    .map(config -> CompletableFuture.runAsync(config::saveConfigs))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } else {
            configfiles.values().forEach(ConfigsInstance::saveConfigs);
        }
    }

    public static void acceptTransformedConfigs() {
        Set<ConfigsInstance> toReload = new HashSet<>();
        for (Map.Entry<TransformedConfig, String[]> entry : needTransformedConfigs.entrySet()) {
            String[] config = entry.getValue();
            TransformedConfig transformedConfig = entry.getKey();
            ConfigsInstance origin = getConfigs(config[0]);
            ConfigsInstance target = getConfigs(config[1]);
            if (origin == null || target == null) continue;
            CommentedFileConfig originConfig = origin.getFileInstance();
            CommentedFileConfig targetConfig = target.getFileInstance();

            final String oldConfigKeyName = config[2];
            final String newConfigKeyName = config[3];
            Object oldValue = originConfig.get(oldConfigKeyName);
            if (oldValue != null) {
                boolean success = true;
                if (transformedConfig.transform()) {
                    try {
                        for (Class<? extends DefaultTransformLogic> logic : transformedConfig.transformLogic()) {
                            oldValue = logic.getDeclaredConstructor().newInstance().transform(oldValue);
                        }
                        oldValue = new DefaultTransformLogic().transform(oldValue);
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