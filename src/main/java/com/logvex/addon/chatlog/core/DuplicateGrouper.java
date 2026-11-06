package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses runs of identical consecutive messages into one line with a repeat count.
 *
 * <p>Only consecutive repeats are grouped, so a message that recurs later still appears in its own
 * place. The sender and content must both match; two different players saying the same thing are
 * kept separate.
 */
public final class DuplicateGrouper {
    private DuplicateGrouper() {
    }

    /** One group: the newest entry of the run and how many times it repeated. */
    public record Group(ChatEntry entry, int count) {
    }

    /**
     * Groups consecutive repeats in a newest-first list, preserving order.
     *
     * @param entries newest-first entries
     * @return groups, newest-first
     */
    public static List<Group> group(List<ChatEntry> entries) {
        List<Group> out = new ArrayList<>();
        if (entries == null) return out;
        for (ChatEntry entry : entries) {
            if (entry == null) continue;
            if (!out.isEmpty()) {
                Group last = out.get(out.size() - 1);
                if (same(last.entry(), entry)) {
                    out.set(out.size() - 1, new Group(last.entry(), last.count() + 1));
                    continue;
                }
            }
            out.add(new Group(entry, 1));
        }
        return out;
    }

    private static boolean same(ChatEntry a, ChatEntry b) {
        return a.sender().equals(b.sender()) && a.content().equals(b.content());
    }

    /** Suffix appended to a grouped line, or an empty string for a single message. */
    public static String suffix(int count) {
        return count > 1 ? " (x" + count + ")" : "";
    }
}
