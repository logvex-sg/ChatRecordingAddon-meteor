package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One unit of work in the bot's queue.
 *
 * <p>A task carries everything the planner and the executor need: what to do ({@link TaskType}),
 * what it is about ({@code itemId}, {@code target}), how much is required, what it depends on, and
 * how far along it is. Dependencies are task ids, so a plan can be reordered, persisted and resumed
 * without losing the graph.
 */
public final class BotTask {
    private static final AtomicLong IDS = new AtomicLong();

    private final long id;
    private TaskType type;
    private String label;
    private TaskPriority priority = TaskPriority.NORMAL;
    private TaskState state = TaskState.PENDING;
    private String itemId = "";
    private int required;
    private int progress;
    private final Set<Long> dependsOn = new LinkedHashSet<>();
    private boolean repeat;
    private String condition = "";
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private String failureReason = "";
    private long createdAt;
    private long startedAt;
    private long finishedAt;

    public BotTask(TaskType type, String label) {
        this.id = IDS.incrementAndGet();
        this.type = type;
        this.label = label;
    }

    /** Restores a task with a fixed id, used when loading persisted memory. */
    public BotTask(long id, TaskType type, String label) {
        this.id = id;
        this.type = type;
        this.label = label;
        IDS.accumulateAndGet(id, Math::max);
    }

    // ------------------------------------------------------------------ accessors

    public long id() {
        return id;
    }

    public TaskType type() {
        return type;
    }

    public BotTask type(TaskType value) {
        this.type = value;
        return this;
    }

    public String label() {
        return label;
    }

    public BotTask label(String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    public TaskPriority priority() {
        return priority;
    }

    public BotTask priority(TaskPriority value) {
        if (value != null) this.priority = value;
        return this;
    }

    public TaskState state() {
        return state;
    }

    public void setState(TaskState value) {
        if (value != null) this.state = value;
    }

    public String itemId() {
        return itemId;
    }

    public BotTask itemId(String value) {
        this.itemId = value == null ? "" : value;
        return this;
    }

    public int required() {
        return required;
    }

    public BotTask required(int value) {
        this.required = Math.max(0, value);
        return this;
    }

    public int progress() {
        return progress;
    }

    public void setProgress(int value) {
        this.progress = Math.max(0, value);
    }

    public void addProgress(int delta) {
        if (delta > 0) this.progress = Math.min(required, progress + delta);
    }

    public boolean complete() {
        return required <= 0 || progress >= required;
    }

    public int remaining() {
        return Math.max(0, required - progress);
    }

    public Set<Long> dependsOn() {
        return dependsOn;
    }

    public BotTask dependOn(long taskId) {
        if (taskId != id) dependsOn.add(taskId);
        return this;
    }

    public boolean repeat() {
        return repeat;
    }

    public BotTask repeat(boolean value) {
        this.repeat = value;
        return this;
    }

    public String condition() {
        return condition;
    }

    public BotTask condition(String value) {
        this.condition = value == null ? "" : value;
        return this;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public BotTask setParameter(String key, String value) {
        if (key != null) parameters.put(key, value == null ? "" : value);
        return this;
    }

    public String parameter(String key, String fallback) {
        return parameters.getOrDefault(key, fallback);
    }

    public String failureReason() {
        return failureReason;
    }

    public void fail(String reason) {
        this.state = TaskState.FAILED;
        this.failureReason = reason == null ? "" : reason;
        this.finishedAt = System.currentTimeMillis();
    }

    public long createdAt() {
        return createdAt;
    }

    public void setCreatedAt(long value) {
        this.createdAt = value;
    }

    public long startedAt() {
        return startedAt;
    }

    public void setStartedAt(long value) {
        this.startedAt = value;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long value) {
        this.finishedAt = value;
    }

    // ------------------------------------------------------------------ helpers

    /** A short, stable description for chat and the GUI. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (required > 0) sb.append(required).append(" x ");
        if (!itemId.isEmpty()) sb.append(prettyId(itemId));
        else sb.append(label);
        return sb.toString();
    }

    /** Progress as a fraction in {@code [0, 1]}. A task with no required amount is complete. */
    public double fraction() {
        if (required <= 0) return 1.0;
        return Math.min(1.0, (double) progress / required);
    }

    /** Deep copy, used when a plan is snapshotted for the GUI or for memory. */
    public BotTask copy() {
        BotTask copy = new BotTask(id, type, label);
        copy.priority = priority;
        copy.state = state;
        copy.itemId = itemId;
        copy.required = required;
        copy.progress = progress;
        copy.dependsOn.addAll(dependsOn);
        copy.repeat = repeat;
        copy.condition = condition;
        copy.parameters.putAll(parameters);
        copy.failureReason = failureReason;
        copy.createdAt = createdAt;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        return copy;
    }

    public List<Long> dependencyList() {
        return new ArrayList<>(dependsOn);
    }

    private static String prettyId(String id) {
        int colon = id.indexOf(':');
        String path = colon < 0 ? id : id.substring(colon + 1);
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder(path.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() == 0 ? path : sb.toString();
    }

    @Override
    public String toString() {
        return "#" + id + " " + type + " " + describe() + " [" + state + " " + progress + "/" + required + "]";
    }
}
