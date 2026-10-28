package me.earthme.luminol.api.config;

import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Factory for {@link WorldValue} instances.
 * <p>
 * The server installs a concrete implementation during startup. Plugins and
 * config modules use the static methods on this class.
 * <p>
 * Example:
 * <pre>{@code
 * @ConfigInfo(name = "enabled")
 * public static final WorldValue<Boolean> enabled = WorldValues.key(false);
 *
 * if (enabled.get()) { ... }
 * if (enabled.get(player.getWorld())) { ... }
 * }</pre>
 */
public abstract class WorldValues {
    private static volatile WorldValues instance;

    /**
     * The installed factory.
     *
     * @return the server implementation
     * @throws IllegalStateException if the server has not installed a factory yet
     */
    public static @NotNull WorldValues getInstance() {
        WorldValues current = instance;
        if (current == null) {
            throw new IllegalStateException("WorldValues has not been installed");
        }
        return current;
    }

    /**
     * Installs the server factory used by {@link #builder()}, {@link #key(Object)} and {@link WorldValue#get()}.
     *
     * @param instance the server implementation
     */
    @ApiStatus.Internal
    public static void setInstance(@NotNull WorldValues instance) {
        WorldValues.instance = Objects.requireNonNull(instance, "instance");
    }

    /**
     * Creates a builder for a world-config value.
     *
     * @param <T> the value type
     * @return a new builder
     */
    public static <T> @NotNull WorldValueBuilder<T> builder() {
        return getInstance().createBuilder();
    }

    /**
     * Creates a world-config value with a global default.
     *
     * @param global the default used when no world is available, and as the
     *               starting value of each world's copy
     * @param <T>    the value type
     * @return a new {@link WorldValue}
     */
    public static <T> @NotNull WorldValue<T> key(T global) {
        return WorldValues.<T>builder().global(global).build();
    }

    /**
     * Resolves {@code key} for the current region world.
     *
     * @param key the world-config value
     * @param <T> the value type
     * @return the world value, or the key's global default
     */
    public static <T> T get(@NotNull WorldValue<T> key) {
        return get(null, key);
    }

    /**
     * Resolves {@code key} for {@code world}. A null world uses the current region world.
     *
     * @param world the Bukkit world, or {@code null} for the current region
     * @param key   the world-config value
     * @param <T>   the value type
     * @return the world value, or the key's global default
     */
    public static <T> T get(@Nullable World world, @NotNull WorldValue<T> key) {
        Objects.requireNonNull(key, "key");
        WorldValues factory = getInstance();
        if (world == null) {
            return factory.resolveCurrent(key);
        }
        return factory.resolve(world, key);
    }

    /**
     * Binds a value to a loaded config path.
     *
     * @param key        the world-config value
     * @param configName the configuration instance name
     * @param path       the dotted config path
     */
    @ApiStatus.Internal
    public static void bind(@NotNull WorldValue<?> key, @NotNull String configName, @NotNull String path) {
        getInstance().bindValue(key, configName, path);
    }

    /**
     * Finds the value bound to {@code configName} / {@code path}.
     *
     * @param configName the configuration instance name
     * @param path       the dotted config path
     * @return the bound value, or {@code null}
     */
    @ApiStatus.Internal
    public static @Nullable WorldValue<?> keyOf(@NotNull String configName, @NotNull String path) {
        return getInstance().findValue(configName, path);
    }

    /**
     * Copies every bound value's live global default into a snapshot array.
     *
     * @return a new snapshot of global values, indexed by {@link WorldValue#index()}
     */
    @ApiStatus.Internal
    public static Object @NotNull [] copyGlobals() {
        return getInstance().snapshotGlobals();
    }

    /**
     * Creates a builder for a world-config value.
     *
     * @param <T> the value type
     * @return a new builder
     */
    protected abstract <T> @NotNull WorldValueBuilder<T> createBuilder();

    /**
     * Resolves {@code key} for the current region world.
     *
     * @param key the world-config value
     * @param <T> the value type
     * @return the world value, or the key's global default
     */
    protected abstract <T> T resolveCurrent(@NotNull WorldValue<T> key);

    /**
     * Resolves {@code key} for {@code world}.
     *
     * @param world the Bukkit world
     * @param key   the world-config value
     * @param <T>   the value type
     * @return the world value, or the key's global default
     */
    protected abstract <T> T resolve(@NotNull World world, @NotNull WorldValue<T> key);

    /**
     * Binds a value to a loaded config path.
     *
     * @param key        the world-config value
     * @param configName the configuration instance name
     * @param path       the dotted config path
     */
    protected abstract void bindValue(@NotNull WorldValue<?> key, @NotNull String configName, @NotNull String path);

    /**
     * Finds the value bound to {@code configName} / {@code path}.
     *
     * @param configName the configuration instance name
     * @param path       the dotted config path
     * @return the bound value, or {@code null}
     */
    protected abstract @Nullable WorldValue<?> findValue(@NotNull String configName, @NotNull String path);

    /**
     * Copies every bound value's live global default into a snapshot array.
     *
     * @return a new snapshot of global values, indexed by {@link WorldValue#index()}
     */
    protected abstract Object @NotNull [] snapshotGlobals();
}
