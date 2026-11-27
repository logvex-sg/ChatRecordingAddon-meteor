package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Words and phrases that stop a message from being answered at all.
 *
 * <p>Separate from switching a rule off, because the two answer different needs. Turning the
 * {@code goodbye} rule off silences every farewell. An ignored word silences one subject while
 * leaving everything else working: an operator who is tired of a running joke adds its word here
 * and still gets answers to {@code !tps} and to a genuine {@code bye}.
 *
 * <p>An entry is matched as a whole word, case-insensitively, so ignoring {@code gg} does not
 * silence {@code egg} or {@code suggest}. A multi-word entry is matched as a phrase with word
 * boundaries at each end. Entries are trimmed and de-duplicated on construction, so a hand-edited
 * list with stray spaces behaves the same as a clean one.
 *
 * <p>An empty list ignores nothing, which is the shipped state: an existing install that upgrades
 * behaves exactly as it did.
 */
public final class IgnoredPhrases {
    /** The instance that ignores nothing. */
    public static final IgnoredPhrases NONE = new IgnoredPhrases(List.of());

    /** Longest list kept, so a hand-edited config cannot make every message cost a long scan. */
    public static final int MAX_ENTRIES = 500;

    private final List<String> phrases;

    private IgnoredPhrases(List<String> phrases) {
        this.phrases = phrases;
    }

    /**
     * Builds the filter from configuration lines.
     *
     * <p>One entry per line, because that is how a Meteor string list is edited, and a comma inside
     * an entry is common enough in a phrase that treating commas as separators would break it.
     */
    public static IgnoredPhrases of(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) return NONE;
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) continue;
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String folded = trimmed.toLowerCase(Locale.ROOT);
            if (out.contains(folded)) continue;
            out.add(folded);
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out.isEmpty() ? NONE : new IgnoredPhrases(List.copyOf(out));
    }

    /** Builds the filter from one comma-separated line, for a caller with a single setting. */
    public static IgnoredPhrases ofLine(String line) {
        if (line == null || line.isBlank()) return NONE;
        return of(List.of(line.split(",")));
    }

    /** True when nothing is ignored, so callers can skip the scan entirely. */
    public boolean isEmpty() {
        return phrases.isEmpty();
    }

    /** The configured entries, lower-cased and trimmed. */
    public List<String> phrases() {
        return phrases;
    }

    public int size() {
        return phrases.size();
    }

    /**
     * True when {@code content} contains one of the ignored entries.
     *
     * @param content the plain text of the incoming message, or null
     */
    public boolean matches(String content) {
        if (phrases.isEmpty() || content == null || content.isEmpty()) return false;
        String haystack = content.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            if (containsWholePhrase(haystack, phrase)) return true;
        }
        return false;
    }

    /** The first ignored entry found in {@code content}, or null. Used for the diagnostic log. */
    public String firstMatch(String content) {
        if (phrases.isEmpty() || content == null || content.isEmpty()) return null;
        String haystack = content.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            if (containsWholePhrase(haystack, phrase)) return phrase;
        }
        return null;
    }

    /**
     * A whole-word search for a phrase.
     *
     * <p>Written out rather than compiled to a pattern: an operator's entry is a literal, so a word
     * containing {@code .} or {@code *} must not behave like a regular expression.
     */
    private static boolean containsWholePhrase(String haystack, String phrase) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(phrase, from);
            if (at < 0) return false;
            int end = at + phrase.length();
            boolean leftOk = at == 0 || !MatchMode.isWordChar(haystack.charAt(at - 1));
            boolean rightOk = end >= haystack.length() || !MatchMode.isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }
}
