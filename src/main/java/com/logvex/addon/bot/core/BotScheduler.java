package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs recurring and delayed work without a background thread.
 *
 * <p>Everything the bot does happens on the client thread, so the scheduler is a simple list of
 * entries checked once per tick. Each entry runs at most once per interval, and a one-shot entry
 * removes itself after firing. This avoids the thread-safety problems that come with a timer thread
 * touching the world, and keeps all work inside the safe tick boundary.
 */
public final class BotScheduler {
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        final String name;
        final long intervalMs;
        final Runnable action;
        long nextAt;
        boolean repeating;
        boolean done;

        Entry(String name, long intervalMs, Runnable action, long nextAt, boolean repeating) {
            this.name = name;
            this.intervalMs = intervalMs;
            this.action = action;
            this.nextAt = nextAt;
            this.repeating = repeating;
        }
    }

    /** Schedules {@code action} to run every {@code intervalMs}, starting after one interval. */
    public void every(String name, long intervalMs, Runnable action) {
        if (action == null || intervalMs <= 0) return;
        entries.add(new Entry(name, intervalMs, action, System.currentTimeMillis() + intervalMs, true));
    }

    /** Schedules {@code action} to run once after {@code delayMs}. */
    public void after(String name, long delayMs, Runnable action) {
        if (action == null) return;
        entries.add(new Entry(name, Math.max(0, delayMs), action,
            System.currentTimeMillis() + Math.max(0, delayMs), false));
    }

    /** Runs every entry whose time has come. Bounded by the number of entries, never blocks. */
    public void tick() {
        long now = System.currentTimeMillis();
        List<Entry> toRemove = null;
        for (Entry entry : entries) {
            if (entry.done || now < entry.nextAt) continue;
            try {
                entry.action.run();
            } catch (RuntimeException ignored) {
                // A scheduled action must never break the tick loop.
            }
            if (entry.repeating) {
                entry.nextAt = now + entry.intervalMs;
            } else {
                entry.done = true;
                if (toRemove == null) toRemove = new ArrayList<>();
                toRemove.add(entry);
            }
        }
        if (toRemove != null) entries.removeAll(toRemove);
    }

    public void cancel(String name) {
        entries.removeIf(entry -> entry.name.equals(name));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
