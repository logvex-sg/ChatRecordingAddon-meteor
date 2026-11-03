package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate statistics for a session plus a bounded history of finished builds.
 *
 * <p>Only the addon's own actions are counted: blocks it placed, blocks it skipped, failures it hit
 * and the time it spent. Nothing here is inferred from the world beyond what the engine verified.
 */
public final class BuildStatistics {
    /** One finished build, for the history list. */
    public record Session(String name, long startedAt, long durationMs, int blocksPlaced,
                          int blocksFailed, int blocksSkipped, double percent, boolean completed) {
        public String summary() {
            return name + ": " + blocksPlaced + " placed"
                + (blocksFailed > 0 ? ", " + blocksFailed + " failed" : "")
                + (blocksSkipped > 0 ? ", " + blocksSkipped + " skipped" : "")
                + " (" + com.logvex.addon.common.Messages.decimal(percent, 1) + "%)";
        }
    }

    private static final int HISTORY_LIMIT = 20;

    private long totalPlaced;
    private long totalFailed;
    private long totalSkipped;
    private long totalSessions;
    private long totalActiveMs;
    private final List<Session> history = new ArrayList<>();

    public void recordPlacement() {
        totalPlaced++;
    }

    public void recordPlacements(int count) {
        if (count > 0) totalPlaced += count;
    }

    public void recordFailure() {
        totalFailed++;
    }

    public void recordSkip() {
        totalSkipped++;
    }

    /** Records a finished build, adding it to the bounded history. */
    public void recordSession(Session session) {
        if (session == null) return;
        totalSessions++;
        totalActiveMs += Math.max(0, session.durationMs());
        history.add(0, session);
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
    }

    public long totalPlaced() {
        return totalPlaced;
    }

    public long totalFailed() {
        return totalFailed;
    }

    public long totalSkipped() {
        return totalSkipped;
    }

    public long totalSessions() {
        return totalSessions;
    }

    public long totalActiveMs() {
        return totalActiveMs;
    }

    public List<Session> history() {
        return List.copyOf(history);
    }

    /** Average blocks placed per active minute, or 0 when nothing has run. */
    public double placementRate() {
        if (totalActiveMs <= 0) return 0;
        return totalPlaced / (totalActiveMs / 60000.0);
    }

    public void reset() {
        totalPlaced = 0;
        totalFailed = 0;
        totalSkipped = 0;
        totalSessions = 0;
        totalActiveMs = 0;
        history.clear();
    }
}
