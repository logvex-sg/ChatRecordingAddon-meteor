package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Remembers which entries a highlight notification has already announced.
 *
 * <p>Highlight notifications are driven by the recording stream, so each entry is offered exactly
 * once. This guard exists because the earlier implementation instead polled the last few entries on
 * every tick: the same highlighted line was re-read twenty times a second and re-logged each time,
 * which buried the log in identical lines. Keeping the guard makes a duplicate delivery — a
 * re-record, a listener registered twice — harmless rather than a flood.
 *
 * <p>Identity is the entry's timestamp together with its content and sender: two genuinely separate
 * messages can share a millisecond, but not the same text from the same sender in that millisecond.
 * Only the most recent identities are remembered, so memory stays bounded on a long session.
 */
public final class HighlightAnnouncer {
    private static final int REMEMBERED = 64;

    private final Deque<String> announced = new ArrayDeque<>();

    /** True when this entry has not been announced yet; records it as announced. */
    public boolean claim(ChatEntry entry) {
        if (entry == null) return false;
        String identity = identity(entry);
        if (announced.contains(identity)) return false;

        announced.addLast(identity);
        while (announced.size() > REMEMBERED) announced.removeFirst();
        return true;
    }

    /** Forgets every announcement, so the next entry is announced again. */
    public void reset() {
        announced.clear();
    }

    public int remembered() {
        return announced.size();
    }

    private static String identity(ChatEntry entry) {
        return entry.timestamp() + "\u0000" + entry.sender() + "\u0000" + entry.content();
    }
}
