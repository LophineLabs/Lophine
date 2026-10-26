package me.earthme.luminol.api.config.annotation;

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

    /**
     * When true, this entry can be overridden per world.
     * The global file still stores the default. A world file only stores an override;
     * if the world has no value, the global default is used.
     */
    boolean isWorldConfig() default false;
}
