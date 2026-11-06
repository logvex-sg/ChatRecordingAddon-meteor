package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered set of {@link ChatFilterRule}s and the decision they produce.
 *
 * <p>Rules are evaluated top to bottom and the first match wins, which is what makes a specific
 * {@code ALLOW} able to punch a hole in a broad {@code HIDE}. When nothing matches, the
 * {@link #defaultAction() default} applies.
 *
 * <p>The set is immutable once built, so a filter decision can never observe a half-edited rule
 * list: the module rebuilds a new set whenever its settings change and swaps it in atomically.
 */
public final class ChatFilterRuleSet {
    private final List<ChatFilterRule> rules;
    private final ChatFilterRule.Action defaultAction;

    private ChatFilterRuleSet(List<ChatFilterRule> rules, ChatFilterRule.Action defaultAction) {
        this.rules = List.copyOf(rules);
        this.defaultAction = defaultAction == null ? ChatFilterRule.Action.ALLOW : defaultAction;
    }

    /** Builds a set, dropping disabled and null rules while preserving order. */
    public static ChatFilterRuleSet of(List<ChatFilterRule> rules, ChatFilterRule.Action defaultAction) {
        List<ChatFilterRule> enabled = new ArrayList<>();
        if (rules != null) {
            for (ChatFilterRule rule : rules) {
                if (rule != null && rule.enabled()) enabled.add(rule);
            }
        }
        return new ChatFilterRuleSet(enabled, defaultAction);
    }

    /** A set that hides nothing. */
    public static ChatFilterRuleSet empty() {
        return new ChatFilterRuleSet(List.of(), ChatFilterRule.Action.ALLOW);
    }

    public List<ChatFilterRule> rules() {
        return rules;
    }

    public ChatFilterRule.Action defaultAction() {
        return defaultAction;
    }

    public boolean isEmpty() {
        return rules.isEmpty() && defaultAction == ChatFilterRule.Action.ALLOW;
    }

    /** The decision for {@code entry}: the first matching rule, else the default. */
    public ChatFilterRule.Action decide(ChatEntry entry) {
        if (entry == null) return ChatFilterRule.Action.ALLOW;
        for (ChatFilterRule rule : rules) {
            if (rule.matches(entry)) return rule.action();
        }
        return defaultAction;
    }

    /** True when the entry should be suppressed. */
    public boolean hides(ChatEntry entry) {
        return decide(entry) == ChatFilterRule.Action.HIDE;
    }
}
