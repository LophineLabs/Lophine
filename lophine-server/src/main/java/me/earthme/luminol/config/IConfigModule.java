package me.earthme.luminol.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface IConfigModule {
    default void beforeFinalLoad() {
    }

    default void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
    }

    default void onUnloaded(CommentedFileConfig configInstance) {
    }

    default <T> T get(String keyName, T defaultValue, @NotNull CommentedFileConfig config) {
        if (!config.contains(keyName)) {
            config.set(keyName, defaultValue);
            return defaultValue;
        }

        return config.get(keyName);
    }
}