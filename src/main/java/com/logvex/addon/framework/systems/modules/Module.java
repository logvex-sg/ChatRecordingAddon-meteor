package com.logvex.addon.framework.systems.modules;

import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.orbit.EventBus;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.Settings;
import com.logvex.addon.framework.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;

/**
 * Base class for every feature module.
 *
 * <p>A module owns settings and is toggled on and off. While active it is subscribed to the
 * {@link EventBus} and receives {@link TickEvent.Post}; its {@link #onActivate()} and
 * {@link #onDeactivate()} hooks are the place to acquire and release resources. Toggling is
 * idempotent: calling {@link #toggle()} twice ends in the state it started from.
 */
public abstract class Module {
    protected final MinecraftClient mc = MinecraftClient.getInstance();

    public final Category category;
    public final String name;
    public final String title;
    public final String description;
    public final String[] aliases;

    public final Settings settings = new Settings();
    public final EventBus eventBus;

    public boolean autoSubscribe = true;
    public boolean chatFeedback = true;

    private boolean active;
    /** A runnable executed on each client tick; kept so a module need not be an event listener. */
    private Runnable ticker;

    protected Module(Category category, String name, String description, String... aliases) {
        this.category = category;
        this.name = name;
        this.title = nameToTitle(name);
        this.description = description;
        this.aliases = aliases;
        this.eventBus = Modules.get().eventBus();
    }

    /** Registers a per-tick callback; the module must be active for it to run. */
    protected void setTicker(Runnable ticker) {
        this.ticker = ticker;
    }

    public void onActivate() {
    }

    public void onDeactivate() {
    }

    public void toggle() {
        if (!active) {
            active = true;
            Modules.get().addActive(this);
            // One subscription covers both the module's own @EventHandler methods and the ticker.
            if (autoSubscribe) eventBus.subscribe(this);
            onActivate();
        } else {
            if (autoSubscribe) eventBus.unsubscribe(this);
            onDeactivate();
            active = false;
            Modules.get().removeActive(this);
        }
    }

    @EventHandler
    private void onTickInternal(TickEvent.Post event) {
        if (ticker != null) ticker.run();
    }

    public boolean isActive() {
        return active;
    }

    public String getInfoString() {
        return null;
    }

    public WWidget getWidget(GuiTheme theme) {
        return null;
    }

    public void info(String message, Object... args) {
        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.infoPrefix(title, message, args);
    }

    public void warning(String message, Object... args) {
        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.warningPrefix(title, message, args);
    }

    public void error(String message, Object... args) {
        ChatUtils.forceNextPrefixClass(getClass());
        ChatUtils.errorPrefix(title, message, args);
    }

    public void sendToggledMsg() {
        if (!chatFeedback) return;
        ChatUtils.sendMsg(Formatting.GRAY, "%s %s.", title, active ? Formatting.GREEN + "on" : Formatting.RED + "off");
    }

    /** {@code session-stats} becomes {@code Session Stats}. */
    public static String nameToTitle(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder out = new StringBuilder(name.length());
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (c == '-' || c == '_') {
                out.append(' ');
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return title;
    }
}
