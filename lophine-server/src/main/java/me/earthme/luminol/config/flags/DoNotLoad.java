package me.earthme.luminol.config.flags;

import me.earthme.luminol.enums.EnumLoadType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface DoNotLoad {
    EnumLoadType when() default EnumLoadType.ALWAYS;
}