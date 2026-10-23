package me.earthme.luminol.api.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Per-instance customization for {@link LuminolConfigsInstance}.
 * Default methods keep the generic loader behavior; callers attach a dedicated
 * implementation instead of branching on the configuration name.
 */
public interface ConfigsInstanceHooks {
    /**
     * No extra behavior. The generic loader opens the file asynchronously and
     * registers {@code BEFORE_FINAL_LOAD} tasks from each field.
     */
    ConfigsInstanceHooks NONE = new ConfigsInstanceHooks() {
    };

    /**
     * Whether each write must be flushed to disk before callers observe the file.
     *
     * @return {@code true} to open the file with synchronous writes
     */
    default boolean synchronousFileWrites() {
        return false;
    }

    /**
     * Capture state before a reload unloads modules.
     *
     * @param instance the configuration instance being reloaded
     * @return a snapshot passed to {@link #afterReload}, or {@code null}
     */
    @Nullable
    default Object beforeReload(@NotNull LuminolConfigsInstance instance) {
        return null;
    }

    /**
     * Apply side effects after a reload finishes.
     *
     * @param instance the configuration instance that reloaded
     * @param snapshot the value returned by {@link #beforeReload}
     */
    default void afterReload(@NotNull LuminolConfigsInstance instance, @Nullable Object snapshot) {
    }

    /**
     * Wrap module loading, for example to publish a full-file view for validators.
     *
     * @param instance the configuration instance being loaded
     * @param load     the module load step
     * @throws Exception if wrapping or loading fails
     */
    default void aroundLoadModules(@NotNull LuminolConfigsInstance instance, @NotNull Runnable load) throws Exception {
        load.run();
    }

    /**
     * Run immediately before fields are read from the file.
     *
     * @param instance    the configuration instance being loaded
     * @param alreadyInit {@code true} when this is a reload
     */
    default void beforeLoadModules(@NotNull LuminolConfigsInstance instance, boolean alreadyInit) {
    }

    /**
     * Whether each field should register its own {@code BEFORE_FINAL_LOAD} task.
     *
     * @return {@code false} to skip per-field registration and handle it in
     * {@link #afterLoadModules}
     */
    default boolean registerFieldBeforeFinalLoad() {
        return true;
    }

    /**
     * Run after every module field has been applied.
     *
     * @param instance    the configuration instance being loaded
     * @param alreadyInit {@code true} when this is a reload
     */
    default void afterLoadModules(@NotNull LuminolConfigsInstance instance, boolean alreadyInit) {
    }

    /**
     * Optionally convert a file value onto a module field.
     *
     * @param instance the configuration instance being loaded
     * @param field    the module field
     * @param value    the raw file or staged value
     * @return a converted value, or empty to use the generic converter
     */
    default Optional<Object> tryTransform(@NotNull LuminolConfigsInstance instance, @NotNull Field field, Object value) {
        return Optional.empty();
    }

    /**
     * Whether a missing key on reload should restore the captured default onto the field.
     *
     * @return {@code true} to restore defaults for absent keys
     */
    default boolean restoreMissingToDefault() {
        return false;
    }
}
