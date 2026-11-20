package com.logvex.addon.common;

import java.util.List;

/**
 * Takes the first {@code limit} rows of a list and says how many were left over.
 *
 * <p>Every command that prints a bounded list needs the same three facts: which rows to show, how
 * many were hidden, and whether anything was hidden at all. Writing that by hand invites the
 * off-by-one where the counter is advanced by the row that broke the loop, so the remainder is
 * reported as one fewer than it is — or as zero when it should say "1 more".
 */
public final class Rows {

    /**
     * @param shown     the rows to print, at most {@code limit} of them
     * @param remaining how many rows were not shown; zero when the list fitted
     */
    public record Capped<T>(List<T> shown, int remaining) {
        public Capped {
            shown = List.copyOf(shown);
        }

        /** True when at least one row was left out. */
        public boolean truncated() {
            return remaining > 0;
        }
    }

    private Rows() {
    }

    /** Caps {@code rows} at {@code limit}. A non-positive limit shows nothing. */
    public static <T> Capped<T> cap(List<T> rows, int limit) {
        if (rows == null || rows.isEmpty()) return new Capped<>(List.of(), 0);
        int keep = Math.max(0, Math.min(limit, rows.size()));
        return new Capped<>(rows.subList(0, keep), rows.size() - keep);
    }

    /** A short note for the hidden rows, or empty when everything was shown. */
    public static String remainderNote(Capped<?> capped) {
        if (capped == null || !capped.truncated()) return "";
        return capped.remaining() + " more not shown";
    }
}
