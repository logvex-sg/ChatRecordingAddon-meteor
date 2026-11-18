package com.logvex.addon.framework.systems.modules;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Base class for every addon module.
 *
 * <p>It is a Meteor {@link Module} — so it lives in Meteor's registry, is toggled from Meteor's own
 * GUI and keybinds, receives Meteor's events on Meteor's bus, and is saved and loaded by Meteor's
 * config — with two additions the addon needs:
 *
 * <ul>
 *   <li>{@link #getWidget(GuiTheme)} returns the module's custom controls in the addon's own GUI kit.
 *       Meteor's {@code getWidget} builds widgets for Meteor's theme, which the addon does not use,
 *       so this overload is what the addon's settings screen calls.</li>
 *   <li>{@link #setTicker(Runnable)} registers a per-tick callback for modules that would otherwise
 *       have to declare an {@code @EventHandler} just to tick. The callback runs only while the
 *       module is active, exactly like an event handler.</li>
 * </ul>
 */
public abstract class AddonModule extends Module {
    private Runnable ticker;

    protected AddonModule(Category category, String name, String description, String... aliases) {
        super(category, name, description, aliases);
    }

    /** Registers a per-tick callback; the module must be active for it to run. */
    protected void setTicker(Runnable ticker) {
        this.ticker = ticker;
    }

    @EventHandler
    private void onTickInternal(TickEvent.Post event) {
        if (ticker != null) ticker.run();
    }

    /** The module's custom controls in the addon's GUI, or null when it has none. */
    public WWidget getWidget(GuiTheme theme) {
        return null;
    }
}
