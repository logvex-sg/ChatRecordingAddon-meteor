package com.logvex.addon.framework.orbit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event listener. A method may have at most one event parameter; its type
 * selects which events it receives.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler {
    /** Lower values run first. */
    int priority() default 0;
}
