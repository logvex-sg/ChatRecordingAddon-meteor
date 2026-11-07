package com.logvex.addon.update.mc;

import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.mc.NotificationService;
import com.logvex.addon.update.core.Downloader;
import com.logvex.addon.update.core.GitHubReleaseSource;
import com.logvex.addon.update.core.ReleaseSource;
import com.logvex.addon.update.core.UpdateChannel;
import com.logvex.addon.update.core.UpdateEngine;
import com.logvex.addon.update.core.UpdateSnapshot;
import com.logvex.addon.update.core.UpdateState;

import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The process-wide update service.
 *
 * <p>It owns the {@link UpdateEngine} and the one place that knows the release repository. It is
 * started once at bootstrap and never blocks: {@link #check()} and {@link #download()} only queue
 * work on the engine's daemon worker. When a check finds a newer release, or a download finishes or
 * fails, a notification is published so the user learns about it wherever they are.
 */
public final class UpdateService {
    private static final UpdateService INSTANCE = new UpdateService();

    /** The repository releases are read from. Kept here so there is exactly one source of truth. */
    public static final String OWNER = "logvex-sg";
    public static final String REPO = "ChatRecordingAddon-meteor";

    public static UpdateService get() {
        return INSTANCE;
    }

    private final UpdateEngine engine;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile UpdateChannel channel = UpdateChannel.STABLE;
    private volatile UpdateState lastNotified = UpdateState.IDLE;

    private UpdateService() {
        ReleaseSource source = new GitHubReleaseSource(OWNER, REPO, "LogClient/" + LogClientContext.VERSION);
        Downloader downloader = new Downloader("LogClient/" + LogClientContext.VERSION);
        this.engine = new UpdateEngine(source, downloader, updatesDirectory(),
            LogClientContext.VERSION, channel);
        engine.setListener(this::onChanged);
    }

    /** The directory verified updates are saved to: {@code <gamedir>/logclient/updates}. */
    public static Path updatesDirectory() {
        return LogClientContext.FOLDER().toPath().resolve("logclient").resolve("updates");
    }

    public UpdateEngine engine() {
        return engine;
    }

    public UpdateSnapshot snapshot() {
        return engine.snapshot();
    }

    public UpdateChannel channel() {
        return channel;
    }

    public void setChannel(UpdateChannel newChannel) {
        if (newChannel == null) return;
        this.channel = newChannel;
        engine.setChannel(newChannel);
    }

    /** Registers a listener told whenever the snapshot changes; used by the GUI and HUD. */
    public void addListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Queues a release check. Safe to call from the game thread. */
    public void check() {
        engine.checkAsync();
    }

    /** Queues a download of the update the last check found. */
    public void download() {
        engine.downloadAsync();
    }

    private void onChanged(UpdateSnapshot snapshot) {
        UpdateState state = snapshot.state();
        if (state != lastNotified) {
            lastNotified = state;
            notifyFor(state, snapshot);
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving listener must not stop the others.
            }
        }
    }

    private void notifyFor(UpdateState state, UpdateSnapshot snapshot) {
        NotificationService notifications = NotificationService.get();
        switch (state) {
            case AVAILABLE -> notifications.notify(NotificationType.GENERAL, NotificationPriority.NORMAL,
                "Update", "Update available",
                "Version " + snapshot.latestVersion() + " is available (you have "
                    + snapshot.installedVersion() + ").");
            case READY -> notifications.notify(NotificationType.TASK_COMPLETED, NotificationPriority.NORMAL,
                "Update", "Update ready",
                "Version " + snapshot.latestVersion() + " was downloaded and verified. "
                    + "Find it in the updates folder.");
            case FAILED -> notifications.notify(NotificationType.CONFIG_ERROR, NotificationPriority.LOW,
                "Update", "Update check failed", snapshot.statusLine());
            default -> {
                // No notification for the transient states.
            }
        }
    }
}
