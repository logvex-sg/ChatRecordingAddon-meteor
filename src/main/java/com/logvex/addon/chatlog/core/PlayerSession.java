package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Time one player spent on the server, as far as the client could observe it.
 *
 * <p>Join and leave events are the only online signals a client sees, so a session is opened by a
 * join and closed by the matching leave. A player who was already online when the client connected
 * never produced a join, so their first session has no measured duration: {@link #sessions} stays
 * at zero and {@link #totalOnlineMs} reports "unknown" rather than a fabricated figure.
 */
public final class PlayerSession {
    private final String name;
    private long firstSeen;
    private long lastSeen;
    private long totalOnlineMs;
    private int sessions;
    private boolean online;
    private long onlineSince;

    public PlayerSession(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** Total measured online time; only meaningful when {@link #hasMeasuredTime()} is true. */
    public long totalOnlineMs() {
        return totalOnlineMs;
    }

    /** True when at least one complete join/leave session was observed. */
    public boolean hasMeasuredTime() {
        return sessions > 0;
    }

    /** Number of completed join/leave sessions. */
    public int sessions() {
        return sessions;
    }

    /** True while the player is believed to be online (a join was seen with no leave yet). */
    public boolean online() {
        return online;
    }

    /** When the current online stretch started, or 0 when the player is not online. */
    public long onlineSince() {
        return onlineSince;
    }

    /** Online time including the in-progress stretch, for display. */
    public long onlineMs(long now) {
        return totalOnlineMs + (online ? Math.max(0, now - onlineSince) : 0);
    }

    void observe(ChatEntry entry) {
        long at = entry.timestamp();
        if (firstSeen == 0 || at < firstSeen) firstSeen = at;
        if (at > lastSeen) lastSeen = at;
        switch (entry.type()) {
            case JOIN -> {
                // A second join without a leave means the leave was missed; keep the newer stretch.
                online = true;
                onlineSince = at;
            }
            case LEAVE -> {
                if (online) {
                    totalOnlineMs += Math.max(0, at - onlineSince);
                    sessions++;
                }
                online = false;
                onlineSince = 0;
            }
            default -> {
            }
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("first", firstSeen);
        map.put("last", lastSeen);
        map.put("sessions", sessions);
        map.put("onlineMs", totalOnlineMs);
        map.put("online", online);
        return map;
    }
}
