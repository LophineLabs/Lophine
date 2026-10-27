package me.earthme.luminol.api.config;

import me.earthme.luminol.api.config.enums.EnumConfigData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for managing configuration instances and operations.
 * Provides methods for loading, saving, modifying, and querying configuration values.
 */
public interface LuminolConfigsInstance {
    /**
     * Initialize the configuration subsystem for this instance.
     * Implementations should load configuration files, apply defaults and
     * prepare any internal caches or state needed for subsequent operations.
     * This method may perform I/O and therefore can throw an {@link IOException}
     * if initialization fails.
     *
     * @throws IOException if an I/O error occurs while initializing
     */
    void initialize() throws IOException;

    /**
     * Reloads all configurations asynchronously
     *
     * @param keepComments whether to preserve existing comments in the config file
     * @return CompletableFuture that completes when reload is finished
     */
    @NotNull CompletableFuture<Void> reloadAsync(boolean keepComments);

    /**
     * Sets a configuration value by key
     *
     * @param key   the configuration key (dot-separated path)
     * @param value the value to set
     * @return true if the key exists and value was set, false otherwise
     */
    boolean setConfig(String key, Object value);

    /**
     * Remove a configuration entry by its key.
     * If the key does not exist this should be a no-op.
     *
     * @param key the configuration key to remove
     */
    void removeConfig(String key);

    /**
     * Saves all pending configuration changes to disk
     */
    void saveConfigs();

    /**
     * Resets a configuration value to its default
     *
     * @param key the configuration key to reset
     */
    void resetConfig(String key);

    /**
     * Gets the default value of a configuration as string
     *
     * @param key the configuration key
     * @return the default value as string
     */
    Object getDefaultConfig(String key);

    /**
     * Gets the current value of a configuration as string
     *
     * @param key the configuration key
     * @return the current value as string
     */
    String getConfig(String key);

    /**
     * Gets the original (untransformed) value of a configuration
     *
     * @param key the configuration key
     * @param <T> the expected type of the value
     * @return the original configuration value
     */
    <T> T getConfigOrigin(String key);

    /**
     * Whether this instance keeps a per-level copy of world-config keys.
     * Matches the {@code isWorldConfig} flag passed to {@link LuminolConfigBuilder}.
     *
     * @return {@code true} when per-world copies are enabled for this instance
     */
    boolean isWorldConfig();

    /**
     * Whether {@code key} is stored as a per-world value on this instance.
     *
     * @param key the configuration key (dot-separated path)
     * @return {@code true} when the key has a per-level copy
     */
    boolean isWorldConfig(String key);

    /**
     * Gets available suggestions for a configuration key
     *
     * @param key the configuration key
     * @return array of suggestion strings, or null if no suggestions available
     */
    String[] getConfigSuggestions(String key);

    /**
     * Completes a partial configuration path by finding matching keys
     *
     * @param partialPath the partial path to complete
     * @return list of possible completions
     */
    List<String> completeConfigPath(String partialPath);

    /**
     * Gets all configuration paths that start with the given prefix
     *
     * @param currentPath the prefix to search for
     * @return list of matching configuration paths
     */
    List<String> getAllConfigPaths(String currentPath);

    /**
     * Gets configuration data pairs for specified keys with optional features
     *
     * @param list     list of configuration keys
     * @param features data features need to return
     * @return map of configuration data
     */
    Map<String, Map<EnumConfigData, Object>> getData(Collection<String> list, Collection<EnumConfigData> features);

    /**
     * Reads a value without consuming staged entries. Used while validators inspect
     * the complete requested file.
     *
     * @param key the configuration key (dot-separated path)
     * @return the staged, file, or default value, or {@code null} if none exist
     */
    @Nullable
    Object peekConfigValue(String key);

    /**
     * Restores module fields to captured defaults before reading a new file.
     */
    void restoreDefaultFields();

    /**
     * Currently instantiated configuration modules.
     *
     * @return an unmodifiable view of module instances
     */
    @NotNull
    Set<Object> getInstancedModules();

    /**
     * Invokes {@code @NeedRun(BEFORE_FINAL_LOAD)} on a module.
     *
     * @param module the configuration module instance
     */
    void runBeforeFinalLoad(@NotNull Object module);

    /**
     * Queues work that must run after preload and before finalize.
     *
     * @param runnable the task to run before the final load pass
     */
    void registerBeforeFinalLoad(@NotNull Runnable runnable);
}
