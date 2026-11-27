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
    private final ReactionGate gate;

    private TriggerSet(List<TriggerRule> rules, ReactionGate gate) {
        this.rules = List.copyOf(rules);
        this.gate = gate == null ? ReactionGate.open() : gate;
    }

    /** The gate every match is filtered through. */
    public ReactionGate gate() {
        return gate;
    }

    /** A copy of this set with {@code gate} applied, so the gate travels with the rules. */
    public TriggerSet withGate(ReactionGate gate) {
        return new TriggerSet(rules, gate);
    }

    /** Builds a set, dropping null and disabled rules while preserving order. */
    public static TriggerSet of(List<TriggerRule> rules) {
        List<TriggerRule> enabled = new ArrayList<>();
        if (rules != null) {
            for (TriggerRule rule : rules) {
                if (rule != null && rule.enabled() && !rule.pattern().isBlank()) enabled.add(rule);
            }
        }
        return new TriggerSet(enabled, ReactionGate.open());
    }

    /** Builds a set filtered through {@code gate} as well as by each rule's own switches. */
    public static TriggerSet of(List<TriggerRule> rules, ReactionGate gate) {
        return of(rules).withGate(gate);
    }

    public static TriggerSet empty() {
        return new TriggerSet(List.of(), ReactionGate.open());
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
        return firstMatch(sender, content, null);
    }

    /**
     * The first matching rule that also satisfies {@code allowed}.
     *
     * <p>The filter lets the caller exclude a class of rules without the set knowing why. The runtime
     * uses it to keep a prefixed command out of reach of a trigger written for the bare word, while
     * still honouring a trigger the user wrote with the prefix on purpose.
     *
     * @param allowed an extra condition, or null to accept every rule
     */
    public TriggerRule firstMatch(String sender, String content, java.util.function.Predicate<TriggerRule> allowed) {
        return firstMatch(sender, content, allowed, System.currentTimeMillis());
    }

    /**
     * As above, with the clock passed in.
     *
     * <p>The gate is consulted here, inside the matching, rather than left to the caller. That is
     * deliberate: the reported bug was a reaction firing from a path that had not checked the
     * switches, so the check cannot live at the call site where a new call site can forget it.
     * Passing {@code now} keeps the mute testable without a clock.
     */
    public TriggerRule firstMatch(String sender, String content,
                                  java.util.function.Predicate<TriggerRule> allowed, long now) {
        if (content == null || content.isBlank()) return null;
        for (TriggerRule rule : rules) {
            if (allowed != null && !allowed.test(rule)) continue;
            if (!gate.check(rule, sender, content, now).allowed()) continue;
            if (matches(rule, content)) return rule;
        }
        return null;
    }

    /**
     * The first rule that matches, together with why it was refused when it was not allowed.
     *
     * <p>Matching and gating are reported separately so a diagnostic can tell "nothing matched" from
     * "something matched but the category is off". Those two look identical to a user and need
     * different fixes.
     */
    public Match firstMatchExplained(String sender, String content, long now) {
        return firstMatchExplained(sender, content, null, now);
    }

    /**
     * As above, with an extra filter.
     *
     * <p>The filter is applied before the gate, exactly as in {@link #firstMatch}, so a rule excluded
     * by the caller cannot mask a later rule that the caller would accept. Getting this order wrong
     * once made a prefixed command unreachable behind a bare-word trigger.
     */
    public Match firstMatchExplained(String sender, String content,
                                     java.util.function.Predicate<TriggerRule> allowed, long now) {
        if (content == null || content.isBlank()) return Match.none("the message is empty");
        for (TriggerRule rule : rules) {
            if (allowed != null && !allowed.test(rule)) continue;
            if (!matches(rule, content)) continue;
            ReactionGate.Verdict verdict = gate.check(rule, sender, content, now);
            return new Match(rule, verdict.allowed(), verdict.reason());
        }
        return Match.none("no rule matched");
    }

    /**
     * A matched rule and whether the gate allowed it.
     *
     * @param rule    the rule whose phrase matched, or null when nothing did
     * @param allowed whether the gate let it run
     * @param reason  why it was refused; empty when allowed or when nothing matched
     */
    public record Match(TriggerRule rule, boolean allowed, String reason) {
        public static Match none(String reason) {
            return new Match(null, false, reason == null ? "" : reason);
        }

        /** True when a rule matched and the gate allowed it. */
        public boolean acts() {
            return rule != null && allowed;
        }
    }

    /** True when {@code rule}'s pattern is present in {@code text} under its matching options. */
    public static boolean matches(TriggerRule rule, String text) {
        if (rule == null || text == null) return false;
        for (String phrase : rule.phrases()) {
            if (matchesPhrase(rule, phrase, text)) return true;
        }
        return false;
    }

    /**
     * The phrase of {@code rule} that matched {@code text}, or null.
     *
     * <p>Useful for feedback and for the GUI: it tells a user that an alias fired rather than the
     * pattern they wrote.
     */
    public static String matchingPhrase(TriggerRule rule, String text) {
        if (rule == null || text == null) return null;
        for (String phrase : rule.phrases()) {
            if (matchesPhrase(rule, phrase, text)) return phrase;
        }
        return null;
    }

    /**
     * Whether one phrase of a rule matches the line, under the rule's own comparison mode.
     *
     * <p>Delegated to {@link MatchMode}, which also owns the whole-word walk the old boolean pair
     * used. Keeping the comparison in one place is what stops the four modes from drifting apart.
     */
    private static boolean matchesPhrase(TriggerRule rule, String pattern, String text) {
        if (pattern == null || pattern.isBlank()) return false;
        return rule.matchMode().matches(pattern, text, rule.caseSensitive());
    }
}
