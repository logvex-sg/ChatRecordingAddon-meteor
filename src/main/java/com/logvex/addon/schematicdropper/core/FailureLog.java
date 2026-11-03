package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, newest-first log of build problems for the error viewer.
 *
 * <p>Only user-actionable problems are recorded: a placement failure, an unresolved dependency, a
 * skipped conflict or a validation error. The log is capped so a long build cannot exhaust memory,
 * and each entry keeps the position and reason needed to retry it.
 */
public final class FailureLog {
    /** A single recorded problem. */
    public record Entry(long at, BlockPos3 pos, String itemId, PlacementFailure failure,
                        String reason, boolean recoverable) {
        public String describe() {
            String where = pos == null ? "build" : pos.toString();
            return where + ": " + reason + " (" + failure + ")";
        }
    }

    private static final int DEFAULT_LIMIT = 500;

    private final List<Entry> entries = new ArrayList<>();
    private final int limit;

    public FailureLog() {
        this(DEFAULT_LIMIT);
    }

    public FailureLog(int limit) {
        this.limit = Math.max(1, limit);
    }

    public void record(BlockPos3 pos, String itemId, PlacementFailure failure, String reason, long nowMs) {
        PlacementFailure f = failure == null ? PlacementFailure.UNKNOWN : failure;
        entries.add(0, new Entry(nowMs, pos, itemId, f, reason == null ? f.toString() : reason, f.recoverable()));
        while (entries.size() > limit) entries.remove(entries.size() - 1);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Entries whose position can be retried. */
    public List<Entry> retryable() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.pos() != null && e.recoverable()) out.add(e);
        }
        return out;
    }

    public void clear() {
        entries.clear();
    }

    public int limit() {
        return limit;
    }
}
