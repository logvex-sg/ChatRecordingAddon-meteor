package com.logvex.addon.bot.core;

/**
 * The bot's only source of time. Injected so tests can advance time deterministically instead of
 * sleeping, and so the engine never reads {@code System.currentTimeMillis()} directly.
 */
public interface BotClock {
    long now();

    static BotClock system() {
        return System::currentTimeMillis;
    }
}
