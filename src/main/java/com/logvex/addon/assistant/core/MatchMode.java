package com.logvex.addon.assistant.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a trigger phrase is compared against an incoming line.
 *
 * <p>Four modes, because the same phrase wants a different comparison depending on what it is. A
 * command word ({@code !tps}) wants {@link #EXACT} so {@code !tpsx} does not answer it. A courtesy
 * ({@code bye}) wants {@link #WHOLE_WORD} so {@code maybe} does not. A fragment an operator is
 * fishing for wants {@link #SUBSTRING}. A pattern with optional spelling wants {@link #REGEX}.
 *
 * <p>This replaces the old boolean pair {@code caseSensitive}/{@code wholeWord}, which could not
 * express "exact" or "regex" at all and left {@code !tps} matching {@code !tpsomething}. The two
 * booleans are still honoured when a stored rule carries them, so no saved rule changes meaning.
 */
public enum MatchMode {
    /** The whole line, after trimming, equals the phrase. */
    EXACT("exact"),
    /** The phrase appears anywhere in the line. */
    SUBSTRING("substring"),
    /** The phrase appears with a non-word character on each side, so {@code bye} misses {@code maybe}. */
    WHOLE_WORD("whole"),
    /** The phrase is a regular expression, matched anywhere in the line. */
    REGEX("regex");

    private final String id;

    MatchMode(String id) {
        this.id = id;
    }

    /** The stored form: {@code exact}, {@code substring}, {@code whole} or {@code regex}. */
    public String id() {
        return id;
    }

    /** The label shown in the GUI and the companion dashboard. */
    public String label() {
        return switch (this) {
            case EXACT -> "Exact line";
            case SUBSTRING -> "Anywhere in the line";
            case WHOLE_WORD -> "Whole word";
            case REGEX -> "Regular expression";
        };
    }

    /** Parses a stored id, falling back to {@code fallback} for anything unrecognised. */
    public static MatchMode parse(String value, MatchMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "exact", "equals", "equal", "wholeline", "whole-line", "line" -> EXACT;
            case "substring", "contains", "any", "anywhere" -> SUBSTRING;
            case "whole", "word", "wholeword", "whole-word", "wordmatch" -> WHOLE_WORD;
            case "regex", "regexp", "re", "pattern" -> REGEX;
            default -> fallback;
        };
    }

    /**
     * The mode a rule should use, resolving the legacy booleans when no mode was stored.
     *
     * <p>A stored rule written before this enum existed has {@code whole=true} or {@code whole=false}
     * and nothing else, so it must keep behaving exactly as it did: {@code whole} meant whole-word
     * and its absence meant substring. Only a rule that names a mode gets a mode.
     */
    public static MatchMode resolve(MatchMode explicit, boolean wholeWord) {
        if (explicit != null) return explicit;
        return wholeWord ? WHOLE_WORD : SUBSTRING;
    }

    /**
     * True when {@code text} matches {@code phrase} under this mode.
     *
     * <p>An invalid regular expression never matches and never throws: a rule the operator mistyped
     * must be silent, not take the chat pipeline down with it. {@link #compile} reports the problem
     * so the GUI can show it instead of leaving a rule that quietly does nothing.
     *
     * @param caseSensitive whether case is significant
     */
    public boolean matches(String phrase, String text, boolean caseSensitive) {
        if (phrase == null || phrase.isEmpty() || text == null) return false;
        return switch (this) {
            case EXACT -> compare(text.strip(), phrase.strip(), caseSensitive) == 0;
            case SUBSTRING -> fold(text, caseSensitive).contains(fold(phrase, caseSensitive));
            case WHOLE_WORD -> containsWholeWord(text, phrase, caseSensitive);
            case REGEX -> regexMatches(phrase, text, caseSensitive);
        };
    }

    /** A compiled pattern, or the reason it could not be compiled. */
    public record Compiled(Pattern pattern, String problem) {
        public boolean ok() {
            return pattern != null;
        }
    }

    /**
     * Compiles {@code phrase} for this mode, or reports why it cannot be used.
     *
     * <p>Only {@link #REGEX} can fail. Every other mode is a literal comparison, so it always
     * compiles; the returned pattern is then null and {@link Compiled#ok()} is still true, because
     * "there is nothing to compile" is not a problem.
     */
    public Compiled compile(String phrase, boolean caseSensitive) {
        if (this != REGEX) return new Compiled(null, "");
        if (phrase == null || phrase.isBlank()) {
            return new Compiled(null, "The pattern is empty.");
        }
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            return new Compiled(Pattern.compile(phrase, flags), "");
        } catch (PatternSyntaxException e) {
            return new Compiled(null, "That is not a valid regular expression: "
                + e.getDescription() + " at position " + e.getIndex() + ".");
        }
    }

    private static boolean regexMatches(String phrase, String text, boolean caseSensitive) {
        Compiled compiled = REGEX.compile(phrase, caseSensitive);
        if (!compiled.ok()) return false;
        return compiled.pattern().matcher(text).find();
    }

    /**
     * A whole-word search that treats a match at either end of the line as bounded.
     *
     * <p>Walked rather than compiled so an operator's phrase is never interpreted as a pattern: a
     * phrase containing {@code .} or {@code (} is a literal here, which is what the whole-word mode
     * promises.
     */
    private static boolean containsWholeWord(String text, String phrase, boolean caseSensitive) {
        String haystack = fold(text, caseSensitive);
        String needle = fold(phrase, caseSensitive);
        if (needle.isEmpty()) return false;
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

    /** True for a character that counts as part of a word. */
    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int compare(String a, String b, boolean caseSensitive) {
        return caseSensitive ? a.compareTo(b) : a.compareToIgnoreCase(b);
    }

    private static String fold(String value, boolean caseSensitive) {
        return caseSensitive ? value : value.toLowerCase(Locale.ROOT);
    }

    /** Every mode, for a GUI dropdown. */
    public static List<MatchMode> all() {
        return List.of(values());
    }
}
