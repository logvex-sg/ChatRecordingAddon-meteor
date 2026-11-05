package com.logvex.addon.bot.core;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps a {@link TaskType} to the executor that performs it. A type with no executor is reported as
 * unsupported rather than silently skipped, so a user is told why their task did nothing.
 */
public final class ExecutorRegistry {
    private final Map<TaskType, TaskExecutor> executors = new EnumMap<>(TaskType.class);

    public ExecutorRegistry register(TaskExecutor executor) {
        if (executor != null) executors.put(executor.type(), executor);
        return this;
    }

    public TaskExecutor get(TaskType type) {
        return executors.get(type);
    }

    public boolean supports(TaskType type) {
        return executors.containsKey(type);
    }

    public Map<TaskType, TaskExecutor> all() {
        return new EnumMap<>(executors);
    }
}
