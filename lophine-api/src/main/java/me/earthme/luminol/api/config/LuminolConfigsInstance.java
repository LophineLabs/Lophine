package me.earthme.luminol.api.config;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
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
    String getDefaultConfig(String key);

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
     * Completes a partial configuration path with specific depth
     *
     * @param partialPath the partial path to complete
     * @param dotIndex    the maximum number of dots (depth) in the result
     * @return list of possible completions
     */
    List<String> completeConfigPath(String partialPath, int dotIndex);

    /**
     * Gets all configuration paths that start with the given prefix
     *
     * @param currentPath the prefix to search for
     * @return list of matching configuration paths
     */
    List<String> getAllConfigPaths(String currentPath);

    /**
     * Gets all configuration data pairs without prefix filter
     *
     * @return set of all configuration data
     */
    Set<ConfigDataPair> getAllData();

    /**
     * Gets configuration data pairs filtered by prefix
     *
     * @param prefix the prefix to filter by
     * @return set of matching configuration data
     */
    Set<ConfigDataPair> getData(String prefix);

    /**
     * Gets all configuration data pairs with full information (comments and suggestions)
     *
     * @return set of all configuration data with full details
     */
    Set<ConfigDataPair> getAllDataFull();

    /**
     * Gets configuration data pairs with full information filtered by prefix
     *
     * @param prefix the prefix to filter by
     * @return set of matching configuration data with full details
     */
    Set<ConfigDataPair> getDataFull(String prefix);

    /**
     * Gets configuration data pairs with optional comments and suggestions
     *
     * @param prefix           the prefix to filter by
     * @param _comment         whether to include comments
     * @param _withSuggestions whether to include suggestions
     * @return set of matching configuration data
     */
    Set<ConfigDataPair> getData(String prefix, boolean _comment, boolean _withSuggestions);

    /**
     * Gets configuration data pairs for specified keys
     *
     * @param list list of configuration keys
     * @return set of configuration data for the specified keys
     */
    Set<ConfigDataPair> getData(List<String> list);

    /**
     * Gets configuration data pairs with comments for specified keys
     *
     * @param list list of configuration keys
     * @return set of configuration data with comments
     */
    Set<ConfigDataPair> getDataWithComment(List<String> list);

    /**
     * Gets configuration data pairs with full information for specified keys
     *
     * @param list list of configuration keys
     * @return set of configuration data with full details
     */
    Set<ConfigDataPair> getDataFull(List<String> list);

    /**
     * Gets configuration data pairs for specified keys with optional features
     *
     * @param list             list of configuration keys
     * @param _comment         whether to include comments
     * @param _withSuggestions whether to include suggestions
     * @return set of configuration data
     */
    Set<ConfigDataPair> getData(List<String> list, boolean _comment, boolean _withSuggestions);

    /**
     * Remove a configuration entry by its key.
     * If the key does not exist this should be a no-op.
     *
     * @param key the configuration key to remove
     */
    void removeConfig(String key);

    /**
     * Remove multiple configuration entries by their keys.
     * Implementations should attempt to remove each key provided. If some
     * keys do not exist they may be ignored.
     *
     * @param keys an array of configuration keys to remove
     */
    void removeConfig(String[] keys);
}
