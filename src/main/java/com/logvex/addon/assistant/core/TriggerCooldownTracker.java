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
 */
public final class TriggerCooldownTracker {
    private final Map<String, Long> lastFiredAt = new HashMap<>();
    private final Map<String, Long> sessionCounts = new HashMap<>();

    /** True when {@code rule} may fire at {@code now}, and records the fire when it may. */
    public boolean tryFire(TriggerRule rule, long now) {
        if (rule == null) return false;
        String key = rule.pattern().toLowerCase(java.util.Locale.ROOT);
        long count = sessionCounts.getOrDefault(key, 0L);
        if (!rule.withinLimit(count)) return false;

        Long last = lastFiredAt.get(key);
        if (last != null && rule.effectiveCooldownMs() > 0 && now - last < rule.effectiveCooldownMs()) {
            return false;
        }

        lastFiredAt.put(key, now);
        sessionCounts.put(key, count + 1);
        return true;
    }

    /** True when {@code rule} may fire at {@code now} without recording a fire. */
    public boolean canFire(TriggerRule rule, long now) {
        if (rule == null) return false;
        String key = rule.pattern().toLowerCase(java.util.Locale.ROOT);
        if (!rule.withinLimit(sessionCounts.getOrDefault(key, 0L))) return false;
        Long last = lastFiredAt.get(key);
        return last == null || rule.effectiveCooldownMs() <= 0 || now - last >= rule.effectiveCooldownMs();
    }

    /** How many times a pattern has fired this session. */
    public long count(String pattern) {
        if (pattern == null) return 0;
        return sessionCounts.getOrDefault(pattern.toLowerCase(java.util.Locale.ROOT), 0L);
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
            if (pattern != null) keep.add(pattern.toLowerCase(java.util.Locale.ROOT));
        }
        lastFiredAt.keySet().retainAll(keep);
        sessionCounts.keySet().retainAll(keep);
    }
}
