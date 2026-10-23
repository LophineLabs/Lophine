package me.earthme.luminol.api.config.annotation;

import me.earthme.luminol.api.config.TransformLogic;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(TransformedConfig.List.class)
public @interface TransformedConfig {
    String name();

    String[] directory();

    String originInstance() default "";

    boolean transform() default true;

    boolean transformComments() default true;

    Class<? extends TransformLogic>[] transformLogic() default {};

    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        TransformedConfig[] value();
    }
}