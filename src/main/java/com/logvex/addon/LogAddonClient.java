package com.logvex.addon;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint for Log Addon.
 *
 * <p>The addon is standalone: it does not require Meteor Client, and its own event bus, HUD, module
 * registry, GUI kit and command dispatcher are driven by the mixins in
 * {@code com.logvex.addon.framework.mixin}. Baritone is used for navigation only when it happens to
 * be installed; it is detected at runtime and never compiled against.
 */
public class LogAddonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LogAddon.initialize();
    }
}
