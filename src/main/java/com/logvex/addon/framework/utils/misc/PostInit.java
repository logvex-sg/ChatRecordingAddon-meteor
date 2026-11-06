package com.logvex.addon.framework.utils.misc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a static method to run after the named classes are initialised. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostInit {
    Class<?>[] dependencies() default {};
}
