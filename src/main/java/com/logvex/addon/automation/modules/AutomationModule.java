package com.logvex.addon.automation.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.mc.NotificationService;

/**
 * Shared behaviour for the automation modules.
 *
 * <p>Every automation module needs the same three things: it must stop acting when the world goes
 * away, it must reset its transient state on a reconnect, and it must report through the shared
 * notification service rather than printing its own prefix. Putting those here means a module only
 * has to describe what it does.
 */
public abstract class AutomationModule extends Module {
    private boolean worldLoaded;

    protected AutomationModule(String name, String description, String... aliases) {
        super(LogClient.CATEGORY, name, description, aliases);
    }

    /** True when a world and a player are present and the module may act. */
    protected boolean inWorld() {
        return worldLoaded && ready();
    }

    /** True when there is a player in a world. */
    protected boolean ready() {
        return mc.player != null && mc.world != null;
    }

    @EventHandler
    private void onJoined(GameJoinedEvent event) {
        worldLoaded = true;
        onWorldJoin();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        worldLoaded = false;
        onWorldLeave();
    }

    /** Called after the client joins a world. */
    protected void onWorldJoin() {
    }

    /** Called after the client leaves a world; reset transient state here. */
    protected void onWorldLeave() {
    }

    /** Raises a notification through the shared service, which owns all output routing. */
    protected void notifyUser(NotificationType type, String title, String message) {
        NotificationService.get().notify(type, this.name, title, message);
    }
}
