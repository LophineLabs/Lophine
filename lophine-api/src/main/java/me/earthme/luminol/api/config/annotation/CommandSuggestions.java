package me.earthme.luminol.api.config.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CommandSuggestions {
    String[] suggest();
}
