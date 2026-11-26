package com.logvex.addon.assistant.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Enforces each trigger's cooldown and per-session reply limit.
 *
 * <p>Without this, a keyword that appears in every message would make the assistant spam chat. The
 * tracker records the last time each pattern fired and how many times it has fired this session, and
 * answers whether a rule may fire again now. It is deliberately Minecraft-free and time is passed in,
 * so the behaviour is unit-testable.
 *
 * <p>The cooldown is tracked <b>per speaker</b>, not per rule alone. With a rule-wide key, one player
 * asking {@code !tps} would silence that rule for everybody else until the cooldown elapsed — at the
 * shipped three-second cooldown that is long enough to look like the assistant is broken for anyone
 * who happens to ask second. The session limit is per rule, because it exists to cap how often the
 * assistant repeats itself in one world, not to police one player.
 */
public final class TriggerCooldownTracker {
    /** Last fire per pattern and speaker, keyed by {@code pattern\u0000sender}. */
    private final Map<String, Long> lastFiredAt = new HashMap<>();
    private final Map<String, Long> sessionCounts = new HashMap<>();

    /** True when {@code rule} may fire for {@code sender} at {@code now}, and records the fire. */
    public boolean tryFire(TriggerRule rule, long now) {
        return tryFire(rule, "", now);
    }

    /**
     * True when {@code rule} may fire for {@code sender} at {@code now}, and records the fire.
     *
     * @param sender the speaker's name; a blank name shares one cooldown slot, which is what an
     *               unknown sender should get
     */
    public boolean tryFire(TriggerRule rule, String sender, long now) {
        if (rule == null) return false;
        String pattern = key(rule.pattern());
        long count = sessionCounts.getOrDefault(pattern, 0L);
        if (!rule.withinLimit(count)) return false;

        String slot = slot(pattern, sender);
        Long last = lastFiredAt.get(slot);
        if (last != null && rule.effectiveCooldownMs() > 0 && now - last < rule.effectiveCooldownMs()) {
            return false;
        }

        lastFiredAt.put(slot, now);
        sessionCounts.put(pattern, count + 1);
        return true;
    }

    /** True when {@code rule} may fire at {@code now} without recording a fire. */
    public boolean canFire(TriggerRule rule, long now) {
        return canFire(rule, "", now);
    }

    /** True when {@code rule} may fire for {@code sender} at {@code now} without recording a fire. */
    public boolean canFire(TriggerRule rule, String sender, long now) {
        if (rule == null) return false;
        String pattern = key(rule.pattern());
        if (!rule.withinLimit(sessionCounts.getOrDefault(pattern, 0L))) return false;
        Long last = lastFiredAt.get(slot(pattern, sender));
        return last == null || rule.effectiveCooldownMs() <= 0 || now - last >= rule.effectiveCooldownMs();
    }

    /** How many times a pattern has fired this session. */
    public long count(String pattern) {
        if (pattern == null) return 0;
        return sessionCounts.getOrDefault(key(pattern), 0L);
    }

    /** Clears all cooldowns and counts; called on world change and when the rules change. */
    public void reset() {
        lastFiredAt.clear();
        sessionCounts.clear();
    }

    /** Drops any state for patterns that are no longer configured. */
    public void retain(java.util.Collection<String> patterns) {
        if (patterns == null) {
            reset();
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String pattern : patterns) {
            if (pattern != null) keep.add(key(pattern));
        }
        sessionCounts.keySet().retainAll(keep);
        // A slot key is "pattern\u0000sender", so it belongs to a kept pattern when its prefix is
        // one of the kept patterns. Comparing whole slot keys against the pattern set would drop
        // every cooldown on each rebuild and let a keyword fire twice in a row.
        lastFiredAt.keySet().removeIf(slot -> {
            int sep = slot.indexOf(SENDER_SEP);
            String pattern = sep < 0 ? slot : slot.substring(0, sep);
            return !keep.contains(pattern);
        });
    }

    private static final char SENDER_SEP = '\u0000';

    private static String key(String pattern) {
        return pattern == null ? "" : pattern.toLowerCase(java.util.Locale.ROOT);
    }

    private static String slot(String pattern, String sender) {
        String who = sender == null ? "" : sender.toLowerCase(java.util.Locale.ROOT);
        return pattern + SENDER_SEP + who;
    }
}
