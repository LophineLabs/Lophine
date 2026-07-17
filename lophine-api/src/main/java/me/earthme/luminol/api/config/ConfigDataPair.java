package me.earthme.luminol.api.config;

import org.jspecify.annotations.Nullable;

public record ConfigDataPair(
        String key,
        Object value,
        @Nullable String comment,
        @Nullable String[] suggestions
) {
}
