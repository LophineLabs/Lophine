package me.earthme.luminol.api.config;

import org.jetbrains.annotations.NotNull;

/**
 * Builds a {@link WorldValue}.
 *
 * @param <T> the value type
 */
public interface WorldValueBuilder<T> {
    /**
     * Sets the global default copied into each world's snapshot.
     *
     * @param value the default value
     * @return this builder
     */
    @NotNull WorldValueBuilder<T> global(T value);

    /**
     * Builds the world-config value.
     *
     * @return a new {@link WorldValue}
     */
    @NotNull WorldValue<T> build();
}
