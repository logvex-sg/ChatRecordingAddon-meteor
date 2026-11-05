package com.logvex.addon.bot.core;

/** A timestamped event in the bot's recent-events timeline. */
public record BotEvent(long at, Level level, String message) {
    public enum Level {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    @Override
    public String toString() {
        return level + " " + message;
    }
}
