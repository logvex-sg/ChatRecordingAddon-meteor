package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An ordered, bounded, dependency-aware task queue.
 *
 * <p>Tasks are held in insertion order. Selection never runs a task whose dependencies are not
 * {@link TaskState#DONE}; such a task is {@link TaskState#BLOCKED} instead. Among the runnable
 * tasks the highest priority (lowest {@link TaskPriority#weight()}) wins, and ties keep insertion
 * order, so a user's plan runs the way it was written.
 *
 * <p>The queue is bounded by {@link #MAX_TASKS}. Adding past the bound is refused rather than
 * silently dropping the oldest task, because silently discarding a user's objective is worse than
 * telling them the queue is full.
 */
public final class TaskQueue {
    /** Practical upper bound so a runaway planner cannot exhaust memory. */
    public static final int MAX_TASKS = 512;

    private final Map<Long, BotTask> tasks = new LinkedHashMap<>();
    private long sequence;

    /** Adds a task. Returns {@code false} when the queue is full. */
    public boolean add(BotTask task) {
        if (task == null) return false;
        if (tasks.size() >= MAX_TASKS) return false;
        task.setCreatedAt(System.currentTimeMillis());
        task.setParameter("seq", Long.toString(sequence++));
        tasks.put(task.id(), task);
        refreshStates();
        return true;
    }

    /** Inserts a task at a specific index among the pending tasks. */
    public boolean insert(int index, BotTask task) {
        if (task == null || tasks.size() >= MAX_TASKS) return false;
        List<BotTask> ordered = new ArrayList<>(tasks.values());
        int clamped = Math.max(0, Math.min(index, ordered.size()));
        ordered.add(clamped, task);
        task.setCreatedAt(System.currentTimeMillis());
        tasks.clear();
        for (BotTask t : ordered) tasks.put(t.id(), t);
        refreshStates();
        return true;
    }

    public Optional<BotTask> get(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public boolean remove(long id) {
        BotTask removed = tasks.remove(id);
        if (removed == null) return false;
        // Any task that depended on the removed one is no longer blocked by it.
        for (BotTask task : tasks.values()) task.dependsOn().remove(id);
        refreshStates();
        return true;
    }

    /** Cancels every task, marking each cancelled and clearing the queue. */
    public void cancelAll() {
        for (BotTask task : tasks.values()) {
            if (!task.state().isFinished()) task.setState(TaskState.CANCELLED);
        }
    }

    /** Removes every finished task, keeping the live plan readable. */
    public void pruneFinished() {
        tasks.values().removeIf(task -> task.state().isFinished());
        refreshStates();
    }

    public void clear() {
        tasks.clear();
    }

    public List<BotTask> tasks() {
        return new ArrayList<>(tasks.values());
    }

    public int size() {
        return tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public boolean isFull() {
        return tasks.size() >= MAX_TASKS;
    }

    /** True when no task remains that is not finished. */
    public boolean allFinished() {
        for (BotTask task : tasks.values()) {
            if (!task.state().isFinished()) return false;
        }
        return true;
    }

    /** Moves a task to a new position in the queue. */
    public boolean reorder(long id, int newIndex) {
        BotTask task = tasks.get(id);
        if (task == null) return false;
        List<BotTask> ordered = new ArrayList<>(tasks.values());
        ordered.remove(task);
        int clamped = Math.max(0, Math.min(newIndex, ordered.size()));
        ordered.add(clamped, task);
        tasks.clear();
        for (BotTask t : ordered) tasks.put(t.id(), t);
        return true;
    }

    /** Replaces the task with {@code oldId} by {@code replacement}, keeping its queue position. */
    public boolean replace(long oldId, BotTask replacement) {
        if (replacement == null || !tasks.containsKey(oldId)) return false;
        LinkedHashMap<Long, BotTask> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<Long, BotTask> entry : tasks.entrySet()) {
            rebuilt.put(entry.getKey(), entry.getKey() == oldId ? replacement : entry.getValue());
        }
        tasks.clear();
        tasks.putAll(rebuilt);
        // Keep the replacement addressable under the old id so dependencies still resolve.
        return true;
    }

    /** Raises or lowers a task's priority. */
    public boolean setPriority(long id, TaskPriority priority) {
        BotTask task = tasks.get(id);
        if (task == null) return false;
        task.priority(priority);
        return true;
    }

    /**
     * Recomputes {@link TaskState#READY} / {@link TaskState#BLOCKED} for pending tasks based on
     * their dependencies. Finished states are left untouched.
     */
    public void refreshStates() {
        for (BotTask task : tasks.values()) {
            if (task.state() == TaskState.PENDING || task.state() == TaskState.READY
                || task.state() == TaskState.BLOCKED) {
                task.setState(dependenciesSatisfied(task) ? TaskState.READY : TaskState.BLOCKED);
            }
        }
    }

    private boolean dependenciesSatisfied(BotTask task) {
        for (Long depId : task.dependsOn()) {
            BotTask dep = tasks.get(depId);
            if (dep == null) continue; // A removed dependency no longer blocks.
            if (dep.state() != TaskState.DONE) return false;
        }
        return true;
    }

    /** The next task that may run, or empty when everything is finished or blocked. */
    public Optional<BotTask> nextRunnable() {
        refreshStates();
        return tasks.values().stream()
            .filter(task -> task.state() == TaskState.READY)
            .min(Comparator.comparingInt((BotTask t) -> t.priority().weight())
                .thenComparingLong(t -> parseSeq(t.parameter("seq", "0"))));
    }

    /** Marks a task running and stamps its start time. */
    public void markRunning(BotTask task) {
        if (task == null) return;
        task.setState(TaskState.RUNNING);
        // The controller stamps the start time from its own clock; only fill it in when unset, so a
        // test clock and the game clock do not disagree.
        if (task.startedAt() == 0) task.setStartedAt(System.currentTimeMillis());
    }

    /** Marks a task done and refreshes dependents. */
    public void markDone(BotTask task) {
        if (task == null) return;
        task.setState(TaskState.DONE);
        task.setProgress(task.required());
        if (task.finishedAt() == 0) task.setFinishedAt(System.currentTimeMillis());
        refreshStates();
    }

    /**
     * Records that a task ended in a non-success state, keeping that state.
     *
     * <p>Deliberately separate from {@link #markDone}: forcing {@code DONE} onto a failed task is
     * how a failure gets reported as a success.
     */
    public void markFinished(BotTask task) {
        if (task == null) return;
        if (task.finishedAt() == 0) task.setFinishedAt(System.currentTimeMillis());
        refreshStates();
    }

    private static long parseSeq(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /** A compact snapshot for the GUI and for memory. */
    public Map<Long, String> summary() {
        Map<Long, String> out = new HashMap<>();
        for (BotTask task : tasks.values()) out.put(task.id(), task.toString());
        return out;
    }
}
