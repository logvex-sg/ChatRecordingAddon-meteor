package com.logvex.addon.chatlog.core;

/**
 * Decides whether a chat line repeats the previous one closely enough to be collapsed.
 *
 * <p>Spam, a stuck macro and a server announcing the same thing twice all look alike in chat: the
 * same text arriving again. This keeps the first line and lets the caller drop the repeats inside a
 * short window, so the feed stays readable without losing the fact that something was repeated.
 *
 * <p>Only an immediately preceding identical line counts. A player who says the same thing again
 * after other messages is making a new statement, not repeating themselves, so the guard resets on
 * any different line. The window is bounded by time rather than by count so a slow repeat still
 * gets through.
 */
public final class RepeatGuard {
    private String lastKey = "";
    private long lastAt;
    private int run;

    /**
     * @return true when this line should be shown, false when it repeats the previous one
     */
    public synchronized boolean accept(String key, long now, long windowMs) {
        if (key == null) key = "";
        long window = Math.max(0, windowMs);

        boolean repeat = !key.isEmpty() && key.equals(lastKey) && now - lastAt <= window;
        if (repeat) {
            run++;
            // Still within the same run: the clock advances so a long spam burst keeps collapsing.
            lastAt = now;
            return false;
        }

        lastKey = key;
        lastAt = now;
        run = 1;
        return true;
    }

    /** How many times the current line has been seen, including the one that was shown. */
    public synchronized int run() {
        return run;
    }

    /** The key of the line currently being tracked, or an empty string when nothing is tracked. */
    public synchronized String current() {
        return lastKey;
    }

    /** Forgets the tracked line, so the next one is always shown. */
    public synchronized void reset() {
        lastKey = "";
        lastAt = 0;
        run = 0;
    }
}
