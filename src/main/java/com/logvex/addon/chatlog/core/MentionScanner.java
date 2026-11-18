package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds names of interest inside a chat line.
 *
 * <p>A client only sees chat it is sent, so "did anyone mention me?" is a real question a busy
 * server makes hard to answer by eye. This scans a message for the local player's own name and any
 * watched names, with word-boundary awareness so {@code Steve} does not fire inside
 * {@code SteveJobs}. The logic is pure string work, so it can be unit tested without a runtime.
 */
public final class MentionScanner {
    private MentionScanner() {
    }

    /**
     * The distinct names from {@code names} that appear in the entry's content or sender, in the
     * order they are supplied. Blank names and the sender's own name are ignored.
     */
    public static List<String> find(List<String> names, ChatEntry entry) {
        List<String> hits = new ArrayList<>();
        if (names == null || entry == null) return hits;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            if (hits.contains(name)) continue;
            if (name.equalsIgnoreCase(entry.sender())) continue;
            if (contains(entry.content(), name)) hits.add(name);
        }
        return hits;
    }

    /** True when {@code name} appears as a whole word in {@code text}, case-insensitively. */
    public static boolean contains(String text, String name) {
        if (text == null || name == null || name.isBlank()) return false;
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return false;
            int end = at + needle.length();
            boolean leftOk = at == 0 || !isNameChar(haystack.charAt(at - 1));
            boolean rightOk = end >= haystack.length() || !isNameChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
