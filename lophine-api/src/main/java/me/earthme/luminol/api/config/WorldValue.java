package me.earthme.luminol.api.config;

import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A configuration value that each loaded world keeps a copy of.
 * <p>
 * Declare one as a {@code static final} field on a config module. The loader
 * copies the {@linkplain #global() global default} into that world's snapshot,
 * then applies the world's overrides.
 *
 * @param <T> the value type
 */
public abstract class WorldValue<T> {
    /**
     * Creates a builder for a world-config value.
     *
     * @param <T> the value type
     * @return a new builder
     */
    public static <T> WorldValueBuilder<T> builder() {
        return WorldValues.builder();
    }

    /**
     * Resolves this value for the current region world.
     *
     * @return the world value, or {@link #global()}
     */
    public abstract T get();

    /**
     * Resolves this value for {@code world}. A null world uses the current region world.
     *
     * @param world the Bukkit world, or {@code null} for the current region
     * @return the world value, or {@link #global()}
     */
    public abstract T get(@Nullable World world);

    /**
     * The live global default copied into each world's snapshot.
     *
     * @return the global value
     */
    public abstract T global();

    /**
     * Snapshot index assigned when this value was built.
     *
     * @return the index, or {@code -1} if unassigned
     */
    @ApiStatus.Internal
    public abstract int index();

    /**
     * Replaces the live global default.
     *
     * @param value the new global value
     */
    @ApiStatus.Internal
    public abstract void setGlobal(Object value);
}
