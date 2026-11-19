package com.logvex.addon.assistant.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Suppresses a message the assistant has already answered.
 *
 * <p>Servers, chat mods and proxies routinely deliver one line more than once, and a player who
 * repeats themselves should not get the same answer twice in a row. A message counts as a duplicate
 * when the same speaker says the same text again inside {@link #windowMs()} — the window is short so
 * a genuine repeat a minute later still gets answered.
 *
 * <p>The comparison is on the whole message, not on the matched trigger, so two different triggers
 * cannot both fire on one duplicated line.
 */
public final class DuplicateSuppressor {
    /** How many recent messages to remember. */
    private static final int MAX_TRACKED = 64;

    private record Stamp(String key, long at) {
    }

    private final long windowMs;
    private final Deque<Stamp> recent = new ArrayDeque<>();

    public DuplicateSuppressor(long windowMs) {
        this.windowMs = Math.max(0L, windowMs);
    }

    public long windowMs() {
        return windowMs;
    }

    /**
     * @return true when this message is new and may be answered
     */
    public boolean accept(String sender, String content, long now) {
        if (windowMs <= 0L) return true;
        if (content == null || content.isBlank()) return false;

        prune(now);
        String key = fingerprint(sender, content);
        for (Stamp stamp : recent) {
            if (stamp.key().equals(key)) return false;
        }
        recent.addLast(new Stamp(key, now));
        while (recent.size() > MAX_TRACKED) recent.removeFirst();
        return true;
    }

    public void reset() {
        recent.clear();
    }

    private void prune(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst().at() > windowMs) {
            recent.removeFirst();
        }
    }

    private static String fingerprint(String sender, String content) {
        String who = sender == null ? "" : sender.trim().toLowerCase(Locale.ROOT);
        String what = content.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return who + "\u0000" + what;
    }
}
