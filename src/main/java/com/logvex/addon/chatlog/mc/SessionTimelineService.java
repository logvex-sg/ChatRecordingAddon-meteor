package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.SessionTimeline;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.orbit.EventBus;
import com.logvex.addon.framework.orbit.EventHandler;

/**
 * Records session milestones into the shared {@link SessionTimeline}.
 *
 * <p>Subscribed once, independently of any module, so the timeline is complete from the first join
 * rather than starting whenever a user happens to enable the module. It listens to the same events
 * the recorder does, so a milestone is never recorded twice.
 */
public final class SessionTimelineService {
    private static final SessionTimelineService INSTANCE = new SessionTimelineService();

    public static SessionTimelineService get() {
        return INSTANCE;
    }

    private final SessionTimeline timeline = new SessionTimeline();
    private final LogService log = LogService.get();
    private volatile boolean recording = true;
    private boolean started;

    private SessionTimelineService() {
    }

    public void init(EventBus bus) {
        if (bus != null) bus.subscribe(this);
    }

    public SessionTimeline timeline() {
        return timeline;
    }

    /** Applies the configured milestone cap to the shared timeline. */
    public void setCapacity(int capacity) {
        timeline.setCapacity(capacity);
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!recording) return;
        long now = System.currentTimeMillis();
        String server = log.serverName();
        timeline.add(now, SessionTimeline.Kind.SESSION_START, server, "");
        if (!started) {
            started = true;
            log.addListener(entryListener);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!recording) return;
        timeline.add(System.currentTimeMillis(), SessionTimeline.Kind.SESSION_END, log.serverName(), "");
        log.removeListener(entryListener);
        started = false;
    }

    private final LogService.LiveListener entryListener = new LogService.LiveListener() {
        @Override
        public void onEntry(ChatEntry entry) {
            if (recording) timeline.observe(entry);
        }
    };

    /** Detaches the live listener; called when the addon shuts down. */
    public void shutdown() {
        log.removeListener(entryListener);
        started = false;
    }
}
