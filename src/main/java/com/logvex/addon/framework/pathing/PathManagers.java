package com.logvex.addon.framework.pathing;

import com.logvex.addon.LogClient;

/**
 * Selects the navigation backend once, at startup.
 *
 * <p>Baritone is detected by class presence, not by a compile-time dependency. When it is absent the
 * no-op manager is kept, and callers fall back to the built-in walker.
 */
public final class PathManagers {
    private static IPathManager instance = new NopPathManager();

    private PathManagers() {
    }

    public static IPathManager get() {
        return instance;
    }

    public static void init() {
        if (!exists("baritone.api.BaritoneAPI")) {
            LogClient.LOG.info("Path manager: none (Baritone not installed)");
            return;
        }
        try {
            instance = new BaritonePathManager();
            LogClient.LOG.info("Path manager: Baritone");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LogClient.LOG.warn("Baritone present but unusable, falling back to the built-in walker: {}", e.toString());
            instance = new NopPathManager();
        }
    }

    private static boolean exists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
