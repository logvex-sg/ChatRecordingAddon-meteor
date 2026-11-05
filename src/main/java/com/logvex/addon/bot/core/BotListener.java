package com.logvex.addon.bot.core;

/** Receives bot lifecycle events. Implementations must never throw back into the bot. */
public interface BotListener {
    /** The controller changed state. */
    default void onStateChanged(BotState state, String reason) {
    }

    /** A task started running. */
    default void onTaskStarted(BotTask task) {
    }

    /** A task finished (done, failed, cancelled or skipped). */
    default void onTaskFinished(BotTask task) {
    }

    /** The current objective finished. */
    default void onObjectiveCompleted(boolean success, String summary) {
    }

    /** A recoverable problem occurred. */
    default void onWarning(String message) {
    }

    /** A fatal problem occurred. */
    default void onFailure(String message) {
    }

    /** Free-form progress feedback. */
    default void onFeedback(String message) {
    }
}
