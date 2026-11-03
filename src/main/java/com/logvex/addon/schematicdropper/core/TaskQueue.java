package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered queue of builds, processed one at a time.
 *
 * <p>The queue is bounded so a runaway importer cannot grow it without limit, and it exposes the
 * active task plus the pending ones for the GUI. It holds no Minecraft types.
 */
public final class TaskQueue {
    private static final int DEFAULT_LIMIT = 64;

    private final Map<String, BuildTask> tasks = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final int limit;
    private String activeId;
    private long nextId = 1;

    public TaskQueue() {
        this(DEFAULT_LIMIT);
    }

    public TaskQueue(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** Adds a task. @return the new task, or {@code null} when the queue is full */
    public BuildTask enqueue(String name, Schematic schematic, BlockPos3 origin, long nowMs) {
        if (schematic == null) return null;
        if (tasks.size() >= limit) return null;
        String id = "task-" + (nextId++);
        BuildTask task = new BuildTask(id, name, schematic, origin, nowMs);
        tasks.put(id, task);
        order.add(id);
        return task;
    }

    /** The next queued task, moved to {@link BuildTask.Status#RUNNING}, or {@code null}. */
    public BuildTask startNext() {
        if (activeId != null) {
            BuildTask active = tasks.get(activeId);
            if (active != null && active.status() == BuildTask.Status.RUNNING) return active;
        }
        for (String id : order) {
            BuildTask task = tasks.get(id);
            if (task != null && task.status() == BuildTask.Status.QUEUED) {
                task.setStatus(BuildTask.Status.RUNNING);
                activeId = id;
                return task;
            }
        }
        return null;
    }

    public BuildTask active() {
        return activeId == null ? null : tasks.get(activeId);
    }

    public String activeId() {
        return activeId;
    }

    /** Marks the active task finished and clears the active slot. */
    public void finishActive(BuildTask.Status status) {
        BuildTask active = active();
        if (active != null) active.setStatus(status);
        activeId = null;
    }

    public boolean cancel(String id) {
        BuildTask task = tasks.get(id);
        if (task == null) return false;
        task.setStatus(BuildTask.Status.CANCELLED);
        if (id.equals(activeId)) activeId = null;
        return true;
    }

    public boolean remove(String id) {
        if (tasks.remove(id) == null) return false;
        order.remove(id);
        if (id.equals(activeId)) activeId = null;
        return true;
    }

    /** Re-queues a cancelled or failed task. */
    public boolean retry(String id) {
        BuildTask task = tasks.get(id);
        if (task == null) return false;
        if (task.status() == BuildTask.Status.RUNNING) return false;
        task.setStatus(BuildTask.Status.QUEUED);
        task.update(task.placed(), task.failed(), task.total());
        return true;
    }

    public BuildTask get(String id) {
        return tasks.get(id);
    }

    public List<BuildTask> tasks() {
        List<BuildTask> out = new ArrayList<>(order.size());
        for (String id : order) {
            BuildTask task = tasks.get(id);
            if (task != null) out.add(task);
        }
        return out;
    }

    public int size() {
        return tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public int limit() {
        return limit;
    }

    public void clear() {
        tasks.clear();
        order.clear();
        activeId = null;
    }

    /** Removes completed and cancelled tasks, keeping running and queued ones. */
    public int pruneFinished() {
        List<String> remove = new ArrayList<>();
        for (BuildTask task : tasks()) {
            if (task.status() == BuildTask.Status.COMPLETED || task.status() == BuildTask.Status.CANCELLED) {
                remove.add(task.id());
            }
        }
        for (String id : remove) remove(id);
        return remove.size();
    }
}
