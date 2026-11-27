package com.logvex.addon.assistant.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The single place that decides whether a reaction may happen at all.
 *
 * <p>It exists because of how the original bug happened. Every gate that decides "should the
 * assistant answer this?" used to live in a different place: enabled-or-not on the rule, audience on
 * the config, duplicate filtering in the runtime, and a bare-word filter inside one call site. Any
 * path that forgot one of them answered anyway, and the reported {@code bye}/{@code gtg}/{@code cya}
 * replies were the visible symptom.
 *
 * <p>So the checks are collected here, and {@link TriggerSet#firstMatch} consults the gate itself
 * rather than trusting its caller. A trigger therefore cannot fire from a path that forgot the
 * gate, because the gate is inside the matching.
 *
 * <p>The gate answers with a {@link Verdict} that carries the reason, so the diagnostic log can say
 * "ignored: matched ignored word 'gg'" instead of leaving a user to guess why nothing happened.
 */
public final class ReactionGate {
    /** A gate that allows every rule in every category and ignores nothing. */
    public static final ReactionGate OPEN = new ReactionGate(
        EnumSet.allOf(ReactionCategory.class), IgnoredPhrases.NONE, true, 0L);

    private final Set<ReactionCategory> categories;
    private final IgnoredPhrases ignored;
    private final boolean enabled;
    private final long mutedUntil;

    /**
     * @param categories  the categories that may fire
     * @param ignored     words that stop a message from being answered
     * @param enabled     the master switch; false silences every rule
     * @param mutedUntil  epoch millis until which everything is silent, or 0 for no mute
     */
    public ReactionGate(Set<ReactionCategory> categories, IgnoredPhrases ignored,
                        boolean enabled, long mutedUntil) {
        this.categories = categories == null || categories.isEmpty()
            ? Collections.unmodifiableSet(EnumSet.noneOf(ReactionCategory.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(categories));
        this.ignored = ignored == null ? IgnoredPhrases.NONE : ignored;
        this.enabled = enabled;
        this.mutedUntil = Math.max(0L, mutedUntil);
    }

    /** A gate with every category on, no ignored words and no mute. */
    public static ReactionGate open() {
        return OPEN;
    }

    /** The categories that may fire. */
    public Set<ReactionCategory> categories() {
        return categories;
    }

    public boolean categoryEnabled(ReactionCategory category) {
        return category != null && categories.contains(category);
    }

    public IgnoredPhrases ignored() {
        return ignored;
    }

    public boolean enabled() {
        return enabled;
    }

    public long mutedUntil() {
        return mutedUntil;
    }

    /** True when the mute is still in force at {@code now}. */
    public boolean muted(long now) {
        return mutedUntil > 0 && now < mutedUntil;
    }

    /**
     * Whether {@code rule} may answer {@code content} from {@code sender} right now.
     *
     * @param rule    the rule that matched
     * @param sender  the speaker, or an empty string when unknown
     * @param content the message text
     * @param now     epoch millis, for the mute
     * @return a verdict carrying the reason when it refuses
     */
    public Verdict check(TriggerRule rule, String sender, String content, long now) {
        if (rule == null) return Verdict.no("no rule matched");
        if (!enabled) return Verdict.no("reactions are switched off");
        if (muted(now)) return Verdict.no("reactions are paused");
        if (!categoryEnabled(rule.category())) {
            return Verdict.no("the " + rule.category().label() + " category is off");
        }
        if (!rule.senderAllowed(sender)) {
            return Verdict.no("the speaker is not allowed for this rule");
        }
        String ignoredWord = ignored.firstMatch(content);
        if (ignoredWord != null) return Verdict.no("the message contains the ignored word '" + ignoredWord + "'");
        return Verdict.yes();
    }

    /**
     * The outcome of a gate check.
     *
     * <p>{@code allowed} plus a {@code reason}: a refusal always says why, because a silent refusal
     * is indistinguishable from a broken rule and was exactly what made the original bug hard to
     * report.
     */
    public record Verdict(boolean allowed, String reason) {
        /** The shared "allowed, no reason needed" verdict. */
        private static final Verdict ALLOWED = new Verdict(true, "");

        public static Verdict yes() {
            return ALLOWED;
        }

        public static Verdict no(String reason) {
            return new Verdict(false, reason == null ? "" : reason);
        }
    }

    /** The categories a gate has on, for the status screens. */
    public List<ReactionCategory> enabledCategories() {
        return ReactionCategory.all().stream().filter(categories::contains).toList();
    }
}
