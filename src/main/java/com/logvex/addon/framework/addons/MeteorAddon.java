package com.logvex.addon.framework.addons;

/**
 * Base class for the addon entry point.
 *
 * <p>Kept as a thin base so the client entrypoint has a stable shape: initialise the addon, then
 * register categories and the command tree.
 */
public abstract class MeteorAddon {
    public abstract void onInitialize();

    public void onRegisterCategories() {
    }

    public String getPackage() {
        return null;
    }

    public GithubRepo getRepo() {
        return null;
    }
}
