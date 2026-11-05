package com.logvex.addon.bot.core;

/**
 * Performs one {@link TaskType}. An executor is advanced one step at a time from the client tick
 * and must return promptly: it may start an action, but it must not block waiting for the world.
 *
 * <p>An executor never reports success on its own authority. It returns {@link TaskOutcome#DONE}
 * only after it has observed the world or the inventory confirm the effect.
 */
public interface TaskExecutor {
    /** The task type this executor handles. */
    TaskType type();

    /** Called once when the task starts. */
    default void begin(BotTask task, TaskContext context) {
    }

    /** Advances the task by one step. Must not block. */
    TaskOutcome step(BotTask task, TaskContext context);

    /** Called when the task is finished, failed or cancelled, to release any resource. */
    default void end(BotTask task, TaskContext context) {
    }

    /** A short, human-readable description of what the executor is doing right now. */
    default String status(BotTask task) {
        return task.describe();
    }
}
