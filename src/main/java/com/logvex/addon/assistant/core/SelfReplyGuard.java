package com.logvex.addon.assistant.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Recognises a message the assistant produced itself.
 *
 * <p>A chat client sees its own sent messages come back from the server, and a notification is
 * rendered into the same chat box as an incoming line. Without a guard, a reply that happens to
 * contain a trigger phrase — or simply the reply itself arriving as an echo — would fire another
 * reply, and that reply could fire another. The guard is the single place that stops the cycle, so
 * it has to be consulted by every path that can start one, not just by the public-chat path.
 *
 * <p>Matching is on normalised text: case, surrounding spaces and runs of whitespace are ignored, so
 * a server that reformats a line does not slip past. The history is bounded, because only a recent
 * echo matters; keeping every reply forever would eventually refuse a genuine player line.
 */
public final class SelfReplyGuard {
    /** How many recent replies are remembered. */
    public static final int DEFAULT_CAPACITY = 32;

    private final int capacity;
    private final Deque<String> recent = new ArrayDeque<>();

    public SelfReplyGuard() {
        this(DEFAULT_CAPACITY);
    }

    public SelfReplyGuard(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Records a line the assistant is about to send or has just produced. */
    public void record(String text) {
        String key = normalise(text);
        if (key.isEmpty()) return;
        recent.addLast(key);
        while (recent.size() > capacity) recent.removeFirst();
    }

    /** True when {@code text} is one the assistant produced. */
    public boolean isOwn(String text) {
        String key = normalise(text);
        if (key.isEmpty()) return false;
        return recent.contains(key);
    }

    /**
     * True when a message from {@code sender} must be ignored.
     *
     * @param sender  the name the message claims, or an empty string
     * @param ownName the local player's name; a match is always the local player talking
     */
    public boolean shouldIgnore(String sender, String ownName, String text) {
        if (ownName != null && !ownName.isBlank() && sender != null
            && sender.trim().equalsIgnoreCase(ownName.trim())) {
            return true;
        }
        return isOwn(text);
    }

    public void reset() {
        recent.clear();
    }

    public int size() {
        return recent.size();
    }

    /** Lower case, trimmed, single-spaced: what two spellings of the same line have in common. */
    static String normalise(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
