package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
import com.logvex.addon.framework.events.packets.PacketEvent;
import com.logvex.addon.framework.orbit.EventBus;
import com.logvex.addon.framework.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;

/**
 * Feeds observed account names into the shared {@link PlayerIdentityTracker}.
 *
 * <p>Names come only from the server's player-list packets, which carry both a UUID and a name.
 * A rename is therefore reported only when the same UUID is later announced under a different name
 * — the one signal a client can honestly observe. Subscribed once at bootstrap, like the recorder,
 * so a rename is never missed because a module happened to be off.
 */
public final class PlayerIdentityService {
    private static final PlayerIdentityService INSTANCE = new PlayerIdentityService();

    public static PlayerIdentityService get() {
        return INSTANCE;
    }

    /** Notified on the packet thread whenever a rename is observed. */
    public interface RenameListener {
        void onRename(PlayerIdentityTracker.Rename rename);
    }

    private final LogService log = LogService.get();
    private final PlayerIdentityTracker tracker = log.identities();
    private final java.util.List<RenameListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;

    private PlayerIdentityService() {
    }

    public void init(EventBus bus) {
        if (bus != null) bus.subscribe(this);
    }

    public void addListener(RenameListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(RenameListener listener) {
        listeners.remove(listener);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!enabled || event == null) return;
        if (event.packet instanceof PlayerListS2CPacket packet) {
            onPlayerList(packet);
        }
    }

    private void onPlayerList(PlayerListS2CPacket packet) {
        long now = System.currentTimeMillis();
        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            if (entry.profile() == null || entry.profile().getId() == null) continue;
            String name = entry.profile().getName();
            if (name == null || name.isBlank()) continue;
            PlayerIdentityTracker.Rename rename =
                tracker.observe(entry.profile().getId().toString(), name, now);
            if (rename != null) {
                for (RenameListener listener : listeners) {
                    try {
                        listener.onRename(rename);
                    } catch (RuntimeException e) {
                        log.reportError("Rename listener failed: " + e.getMessage());
                    }
                }
            }
        }
    }
}
