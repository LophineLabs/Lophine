package me.earthme.luminol.api.config.annotation;

import me.earthme.luminol.api.config.enums.EnumLoadType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface DoNotLoad {
    EnumLoadType when() default EnumLoadType.ALWAYS;
}