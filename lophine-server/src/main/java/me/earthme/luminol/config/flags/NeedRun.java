package me.earthme.luminol.config.flags;

import me.earthme.luminol.enums.EnumRunnableType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NeedRun {
    EnumRunnableType when();
}
