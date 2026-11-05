package com.logvex.addon.bot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Holds the user's high-level objectives in order and hands them to the planner one at a time.
 *
 * <p>An objective is the sentence the user typed ("gather 256 oak logs"). The manager keeps the
 * queue of objectives, remembers which one is active, and records the outcome of each finished
 * objective in {@link BotMemory}, so the bot can be told a sequence of goals and will work through
 * them in order.
 */
public final class GoalManager {
    /** One user objective. */
    public record Goal(long id, String description, String type, String itemId, int amount) {
    }

    private final Deque<Goal> pending = new ArrayDeque<>();
    private final List<Goal> finished = new ArrayList<>();
    private Goal active;
    private long nextId = 1;

    /** Adds an objective to the end of the queue. */
    public Goal add(String description, String type, String itemId, int amount) {
        Goal goal = new Goal(nextId++, description == null ? "" : description,
            type == null ? "" : type, itemId == null ? "" : itemId, Math.max(0, amount));
        pending.addLast(goal);
        return goal;
    }

    /** The objective currently being worked on, or {@code null}. */
    public Goal active() {
        return active;
    }

    public List<Goal> pending() {
        return new ArrayList<>(pending);
    }

    public List<Goal> finished() {
        return new ArrayList<>(finished);
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean hasWork() {
        return active != null || !pending.isEmpty();
    }

    /** Takes the next objective, or {@code null} when the queue is empty. */
    public Goal next() {
        if (active != null) return active;
        active = pending.pollFirst();
        return active;
    }

    /** Marks the active objective finished and moves it to the history. */
    public void completeActive(boolean success, String reason, BotMemory memory) {
        if (active == null) return;
        finished.add(active);
        if (memory != null) memory.recordObjective(active.description(), success, reason);
        active = null;
    }

    public void clear() {
        pending.clear();
        active = null;
    }

    public void clearHistory() {
        finished.clear();
    }
}
