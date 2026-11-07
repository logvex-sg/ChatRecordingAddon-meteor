package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.PlayerIdentityStore;
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
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-identity-io");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.atomic.AtomicBoolean saveQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean enabled = true;
    private volatile boolean loaded;

    private PlayerIdentityService() {
    }

    public void init(EventBus bus) {
        if (bus != null) bus.subscribe(this);
        loadAsync(null);
    }

    /** The directory the identity index is written to. */
    private static java.nio.file.Path dataDir() {
        return com.logvex.addon.framework.LogClientContext.FOLDER().toPath().resolve("logclient");
    }

    /** Loads the persisted index, then runs {@code onLoaded} on the client thread. */
    public void loadAsync(Runnable onLoaded) {
        io.execute(() -> {
            PlayerIdentityStore.load(dataDir(), tracker);
            loaded = true;
            if (onLoaded == null) return;
            if (com.logvex.addon.framework.LogClientContext.mc == null) onLoaded.run();
            else com.logvex.addon.framework.LogClientContext.mc.execute(onLoaded);
        });
    }

    /** Queues an atomic write of the index; repeat calls coalesce into one pending write. */
    public void saveAsync() {
        if (!loaded) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        io.execute(() -> {
            saveQueued.set(false);
            PlayerIdentityStore.save(dataDir(), tracker);
        });
    }

    /** Flushes the index and stops the writer. Called once at shutdown. */
    public void shutdown() {
        // Only write back when the persisted index was actually loaded first; otherwise this would
        // overwrite a good file with the empty in-memory tracker.
        if (loaded) io.execute(() -> PlayerIdentityStore.save(dataDir(), tracker));
        io.shutdown();
        try {
            if (!io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
                saveAsync();
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
