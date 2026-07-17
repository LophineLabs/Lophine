package me.earthme.luminol.api.config;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Builder interface for creating {@link LuminolConfigsInstance} instances.
 * <p>
 * Provides multiple overloaded factory methods to construct configuration instances
 * with varying levels of customization, including custom base directories,
 * file names, and command names.
 */
public interface LuminolConfigBuilder {
    /**
     * Creates a configuration instance using the default base directory and file name.
     *
     * @param loader the class loader used to load configuration resources
     * @param name   the configuration name identifier
     * @param pack   the package path where configuration classes are located
     * @return a new {@link LuminolConfigsInstance}
     */
    LuminolConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull String name,
            @NotNull String pack
    );

    /**
     * Creates a configuration instance with a custom base directory.
     *
     * @param loader the class loader used to load configuration resources
     * @param base   the base directory where the configuration file is stored
     * @param name   the configuration name identifier
     * @param pack   the package path where configuration classes are located
     * @return a new {@link LuminolConfigsInstance}
     */
    LuminolConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack
    );

    /**
     * Creates a configuration instance with a custom base directory and file name.
     *
     * @param loader    the class loader used to load configuration resources
     * @param base      the base directory where the configuration file is stored
     * @param name      the configuration name identifier
     * @param file_name the configuration file name (without extension)
     * @param pack      the package path where configuration classes are located
     * @return a new {@link LuminolConfigsInstance}
     */
    LuminolConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack
    );

    /**
     * Creates a configuration instance with full customization options.
     *
     * @param loader       the class loader used to load configuration resources
     * @param base         the base directory where the configuration file is stored
     * @param name         the configuration name identifier
     * @param file_name    the configuration file name (without extension)
     * @param command_name the command name associated with this configuration
     * @param pack         the package path where configuration classes are located
     * @return a new {@link LuminolConfigsInstance}
     */
    LuminolConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack
    );
}
