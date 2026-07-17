package me.earthme.luminol.config.flags;

import me.earthme.luminol.enums.EnumConfigCategory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigClassInfo {
    EnumConfigCategory category();

    String name();

    String[] directory() default {};

    String comments() default "";
}
