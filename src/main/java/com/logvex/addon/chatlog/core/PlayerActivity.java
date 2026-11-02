package com.logvex.addon.chatlog.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Observable activity for one player.
 *
 * <p>Only events the client can actually see are counted: joins, leaves, deaths and chat lines.
 * A player who is online but never says anything and never generates an event is invisible to a
 * client, so they are not listed at all.
 */
public final class PlayerActivity {
    private final String name;
    private long firstSeen;
    private long lastSeen;
    private long messageCount;
    private long joinCount;
    private long leaveCount;
    private long deathCount;
    private final Set<String> sessions = new LinkedHashSet<>();

    public PlayerActivity(String name) {
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

    public long messageCount() {
        return messageCount;
    }

    public long joinCount() {
        return joinCount;
    }

    public long leaveCount() {
        return leaveCount;
    }

    public long deathCount() {
        return deathCount;
    }

    public int sessionCount() {
        return sessions.size();
    }

    void observe(ChatEntry entry) {
        if (firstSeen == 0 || entry.timestamp() < firstSeen) firstSeen = entry.timestamp();
        if (entry.timestamp() > lastSeen) lastSeen = entry.timestamp();
        if (!entry.sessionId().isBlank()) sessions.add(entry.sessionId());
        switch (entry.type()) {
            case JOIN -> joinCount++;
            case LEAVE -> leaveCount++;
            case DEATH -> deathCount++;
            default -> {
                if (!entry.sender().isBlank()) messageCount++;
            }
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("first", firstSeen);
        map.put("last", lastSeen);
        map.put("messages", messageCount);
        map.put("joins", joinCount);
        map.put("leaves", leaveCount);
        map.put("deaths", deathCount);
        map.put("sessions", sessionCount());
        return map;
    }
}
