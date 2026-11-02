package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates {@link KeywordRule}s against chat entries.
 *
 * <p>Whole-word matching treats a word as a run of letters/digits/underscore, so {@code base} does
 * not match {@code database} while {@code base-1} still matches {@code base}.
 */
public final class KeywordMatcher {

    private KeywordMatcher() {
    }

    /** Every enabled rule that matches the entry, in rule order. */
    public static List<KeywordRule> matches(List<KeywordRule> rules, ChatEntry entry) {
        List<KeywordRule> hits = new ArrayList<>();
        if (rules == null || entry == null) return hits;
        for (KeywordRule rule : rules) {
            if (rule == null || !rule.enabled()) continue;
            if (matches(rule, entry)) hits.add(rule);
        }
        return hits;
    }

    public static boolean matches(KeywordRule rule, ChatEntry entry) {
        if (rule == null || entry == null) return false;
        return matches(rule, entry.content()) || matches(rule, entry.sender());
    }

    public static boolean matches(KeywordRule rule, String text) {
        if (rule == null || text == null) return false;
        String pattern = rule.pattern();
        if (pattern.isEmpty()) return false;

        String haystack = rule.caseSensitive() ? text : text.toLowerCase(Locale.ROOT);
        String needle = rule.caseSensitive() ? pattern : pattern.toLowerCase(Locale.ROOT);

        if (!rule.wholeWord()) return haystack.contains(needle);

        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return false;
            int end = at + needle.length();
            boolean leftOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    /** True when any enabled rule requests a sound for this entry. */
    public static boolean anySound(List<KeywordRule> rules, ChatEntry entry) {
        for (KeywordRule rule : matches(rules, entry)) {
            if (rule.sound()) return true;
        }
        return false;
    }

    /** True when any enabled rule requests a webhook notification for this entry. */
    public static boolean anyWebhook(List<KeywordRule> rules, ChatEntry entry) {
        for (KeywordRule rule : matches(rules, entry)) {
            if (rule.webhook()) return true;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
