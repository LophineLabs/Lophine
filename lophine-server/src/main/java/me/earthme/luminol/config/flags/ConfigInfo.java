package me.earthme.luminol.config.flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigInfo {
    String name();

    String[] directory() default {};

    String comments() default "";

    boolean allowAutoReset() default true;
}