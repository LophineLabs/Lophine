package me.earthme.luminol.config.flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigInfo {
    String name();

    String[] directory() default {};

    /**
     * Overrides the module's section name while retaining its category and parent directory.
     */
    String section() default "";

    boolean allowAutoReset() default true;
}
