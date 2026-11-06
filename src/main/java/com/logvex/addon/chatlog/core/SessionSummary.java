package com.logvex.addon.chatlog.core;

import com.logvex.addon.common.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a short, readable summary of a recording session from the statistics that were actually
 * collected. It is pure formatting over a {@link StatsSnapshot}, so it can be produced on the
 * client thread the moment a session ends and unit tested without a Minecraft runtime.
 */
public final class SessionSummary {
    private SessionSummary() {
    }

    /** True when there is nothing worth reporting (no session ran, or no message was seen). */
    public static boolean isEmpty(StatsSnapshot snapshot) {
        return snapshot == null || (snapshot.totalMessages() == 0 && snapshot.startedAt() == 0);
    }

    /**
     * A one-line headline, e.g.
     * {@code Session ended: 1,284 messages from 14 players over 1h 02m}.
     */
    public static String headline(StatsSnapshot snapshot) {
        if (isEmpty(snapshot)) return "Session ended: no messages recorded.";
        return Messages.format("Session ended: %s from %s over %s.",
            Messages.plural(snapshot.totalMessages(), "message"),
            Messages.plural(snapshot.uniquePlayers(), "player"),
            Messages.duration(snapshot.durationMs()));
    }

    /** A short breakdown of the session, one line per fact. Never longer than a few lines. */
    public static List<String> lines(StatsSnapshot snapshot) {
        List<String> out = new ArrayList<>();
        if (isEmpty(snapshot)) return out;

        out.add(headline(snapshot));
        if (snapshot.messagesPerMinute() > 0) {
            out.add(Messages.format("Average rate: %s messages per minute.",
                Messages.decimal(snapshot.messagesPerMinute(), 1)));
        }
        if (snapshot.joinEvents() > 0 || snapshot.leaveEvents() > 0) {
            out.add(Messages.format("%s and %s.",
                Messages.plural(snapshot.joinEvents(), "join"),
                Messages.plural(snapshot.leaveEvents(), "leave")));
        }
        if (snapshot.deathEvents() > 0) {
            out.add(Messages.plural(snapshot.deathEvents(), "death") + " recorded.");
        }
        if (snapshot.keywordAlerts() > 0) {
            out.add(Messages.plural(snapshot.keywordAlerts(), "keyword alert") + ".");
        }
        if (!snapshot.topPlayers().isEmpty()) {
            out.add("Most active: " + String.join(", ", snapshot.topPlayers()));
        }
        return out;
    }

    /** A compact single line for the notification bus or a webhook field. */
    public static String compact(StatsSnapshot snapshot) {
        if (isEmpty(snapshot)) return "No messages recorded.";
        return Messages.format("%s, %s, %s, %s.",
            Messages.plural(snapshot.totalMessages(), "message"),
            Messages.plural(snapshot.uniquePlayers(), "player"),
            Messages.plural(snapshot.joinEvents() + snapshot.leaveEvents(), "join/leave"),
            Messages.duration(snapshot.durationMs()));
    }
}
