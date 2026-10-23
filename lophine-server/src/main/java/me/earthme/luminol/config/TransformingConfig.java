package me.earthme.luminol.config;

import me.earthme.luminol.api.config.annotation.TransformedConfig;

public record TransformingConfig(
        TransformedConfig info,
        String origKey,
        String tarKey,
        String origPath,
        String tarPath
) {
}
