package com.logvex.addon.framework.settings;

/** Decides whether a setting is shown, usually so a dependent setting hides when its parent is off. */
@FunctionalInterface
public interface IVisible {
    boolean isVisible();
}
