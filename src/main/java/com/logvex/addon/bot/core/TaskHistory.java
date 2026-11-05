package com.logvex.addon.bot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, newest-first record of tasks that have finished.
 *
 * <p>Bounded on purpose: an unbounded history is a slow memory leak in a long-running session.
 * The oldest entries are dropped once {@link #LIMIT} is reached.
 */
public final class TaskHistory {
    public static final int LIMIT = 200;

    /** One finished task, reduced to what a user needs to see. */
    public record Entry(long taskId, TaskType type, String description, TaskState outcome,
                        int required, int progress, String reason, long finishedAt) {
        public boolean succeeded() {
            return outcome == TaskState.DONE;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    public void record(BotTask task) {
        if (task == null || !task.state().isFinished()) return;
        entries.addFirst(new Entry(task.id(), task.type(), task.describe(), task.state(),
            task.required(), task.progress(), task.failureReason(),
            task.finishedAt() > 0 ? task.finishedAt() : System.currentTimeMillis()));
        while (entries.size() > LIMIT) entries.removeLast();
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    /** How many recorded tasks finished successfully. */
    public int successCount() {
        int count = 0;
        for (Entry entry : entries) if (entry.succeeded()) count++;
        return count;
    }

    public int failureCount() {
        return entries.size() - successCount();
    }
}
