package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An ordered set of {@link TriggerRule}s and the matching they produce.
 *
 * <p>Rules are evaluated top to bottom and the first match wins, which lets a specific rule be
 * placed above a broad one. The set is immutable once built, so a chat message can never observe a
 * half-edited rule list: the module rebuilds a new set whenever its settings change and swaps it in
 * atomically.
 *
 * <p>Matching is deliberately conservative — a rule with no pattern never matches, and a blank
 * message never matches — so an empty or malformed configuration stays silent rather than replying
 * to everything.
 */
public final class TriggerSet {
    private final List<TriggerRule> rules;

    private TriggerSet(List<TriggerRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Builds a set, dropping null and disabled rules while preserving order. */
    public static TriggerSet of(List<TriggerRule> rules) {
        List<TriggerRule> enabled = new ArrayList<>();
        if (rules != null) {
            for (TriggerRule rule : rules) {
                if (rule != null && rule.enabled() && !rule.pattern().isBlank()) enabled.add(rule);
            }
        }
        return new TriggerSet(enabled);
    }

    public static TriggerSet empty() {
        return new TriggerSet(List.of());
    }

    public List<TriggerRule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * The first rule that matches {@code content} from {@code sender}, or null when none does.
     *
     * @param sender the player the message came from, or an empty string when unknown
     */
    public TriggerRule firstMatch(String sender, String content) {
        if (content == null || content.isBlank()) return null;
        for (TriggerRule rule : rules) {
            if (!rule.senderAllowed(sender)) continue;
            if (matches(rule, content)) return rule;
        }
        return null;
    }

    /** True when {@code rule}'s pattern is present in {@code text} under its matching options. */
    public static boolean matches(TriggerRule rule, String text) {
        if (rule == null || text == null) return false;
        String pattern = rule.pattern();
        if (pattern.isBlank()) return false;

        String haystack = rule.caseSensitive() ? text : text.toLowerCase(Locale.ROOT);
        String needle = rule.caseSensitive() ? pattern : pattern.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return false;

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

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
