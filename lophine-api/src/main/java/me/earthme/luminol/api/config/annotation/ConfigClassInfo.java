package me.earthme.luminol.api.config.annotation;

import me.earthme.luminol.api.config.enums.EnumConfigCategory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigClassInfo {
    EnumConfigCategory category();

    String name();

    String[] directory() default {};
}
