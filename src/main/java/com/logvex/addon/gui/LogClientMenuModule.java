package com.logvex.addon.gui;

import com.logvex.addon.LogClient;
import com.logvex.addon.framework.systems.modules.AddonModule;
import net.minecraft.client.MinecraftClient;

/**
 * Opens the addon's own window.
 *
 * <p>The addon's window is its own screen, not part of Meteor's GUI, so this module is the Meteor-side
 * way in: bind it to a key (or toggle it from Meteor's GUI) and it opens the window, then turns
 * itself back off. Binding a key in Meteor is the equivalent of the hotkey the addon used to install
 * itself, and it never clashes with a Meteor keybind because Meteor owns the binding.
 */
public class LogClientMenuModule extends AddonModule {
    public LogClientMenuModule() {
        super(LogClient.CATEGORY, "log-client-menu", "Opens the Log Client window. Bind a key to open it.");
        // The window can be opened from the title screen too, so the module must activate there.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        MinecraftClient.getInstance().setScreen(new ClickGuiScreen());
        // A menu opener is one-shot: turning it off immediately keeps it out of the active list while
        // leaving the window open.
        toggle();
    }
}
