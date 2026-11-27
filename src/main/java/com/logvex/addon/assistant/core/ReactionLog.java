package com.logvex.addon.assistant.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A short, bounded record of what the reaction system did with recent messages.
 *
 * <p>Added because the reported problem was invisible. A user saw the assistant answer {@code bye}
 * and had no way to tell which rule did it, which category it belonged to, or why switching
 * something off had not silenced it. Debugging that meant reading the source.
 *
 * <p>This keeps the last few decisions, each with the reason, so the GUI and the companion dashboard
 * can show "cya: refused, the Farewells category is off" instead of nothing at all. It is a
 * diagnostic, not a log file: the entries live in memory only and are never written to disk, because
 * they contain chat text and this addon is careful about what it persists.
 *
 * <p>Thread-safe: the chat thread writes and the GUI/render thread reads.
 */
public final class ReactionLog {
    /** How many entries are kept. Enough to see a pattern, few enough to stay cheap. */
    public static final int CAPACITY = 60;

    /** What happened to one message. */
    public enum Outcome {
        /** A rule answered it. */
        REPLIED("replied"),
        /** A rule matched but a switch refused it. */
        SKIPPED("skipped"),
        /** No rule matched. */
        NO_MATCH("no match"),
        /** A word on the ignore list stopped it. */
        IGNORED("ignored");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One decision.
     *
     * @param at       epoch millis the message arrived
     * @param sender   who sent it
     * @param excerpt  the message, shortened
     * @param outcome  what happened
     * @param pattern  the rule that matched, or an empty string
     * @param category the rule's category, or null
     * @param reason   why it was skipped, empty when it was not
     */
    public record Entry(long at, String sender, String excerpt, Outcome outcome, String pattern,
                        ReactionCategory category, String reason) {
        /** A one-line rendering for a status screen. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            out.append(outcome.label());
            if (pattern != null && !pattern.isBlank()) out.append(" '").append(pattern).append('\'');
            if (category != null) out.append(" [").append(category.id()).append(']');
            if (reason != null && !reason.isBlank()) out.append(": ").append(reason);
            return out.toString();
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private long replied;
    private long skipped;
    private long ignoredCount;

    /**
     * Records a decision, dropping the oldest when full.
     *
     * <p>An excerpt is capped so a pasted wall of text cannot fill the buffer with one entry.
     */
    public synchronized void record(long at, String sender, String content, Outcome outcome,
                                    TriggerRule rule, String reason) {
        String excerpt = shorten(content);
        String pattern = rule == null ? "" : rule.pattern();
        ReactionCategory category = rule == null ? null : rule.category();
        entries.addFirst(new Entry(at, sender == null ? "" : sender, excerpt, outcome, pattern,
            category, reason == null ? "" : reason));
        while (entries.size() > CAPACITY) entries.removeLast();
        switch (outcome) {
            case REPLIED -> replied++;
            case SKIPPED -> skipped++;
            case IGNORED -> ignoredCount++;
            case NO_MATCH -> {
                // Not counted: a busy server would drown every real figure in "nothing matched".
            }
        }
    }

    /** The newest first. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The newest {@code limit} entries, newest first. */
    public synchronized List<Entry> recent(int limit) {
        if (limit <= 0) return List.of();
        List<Entry> out = new ArrayList<>(Math.min(limit, entries.size()));
        for (Entry entry : entries) {
            if (out.size() >= limit) break;
            out.add(entry);
        }
        return List.copyOf(out);
    }

    public synchronized long replied() {
        return replied;
    }

    public synchronized long skipped() {
        return skipped;
    }

    public synchronized long ignoredCount() {
        return ignoredCount;
    }

    public synchronized void clear() {
        entries.clear();
        replied = 0;
        skipped = 0;
        ignoredCount = 0;
    }

    private static String shorten(String content) {
        if (content == null) return "";
        String flat = content.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() <= 60 ? flat : flat.substring(0, 59) + "\u2026";
    }
}
