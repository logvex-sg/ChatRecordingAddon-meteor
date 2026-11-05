package com.logvex.addon.bot.core;

import java.util.Optional;

/** Persistence for {@link BotMemory}. The Minecraft layer supplies a file-backed implementation. */
public interface MemoryStore {
    /** Loads the stored memory, or empty when nothing valid is stored yet. */
    Optional<BotMemory> load();

    /** Persists the memory. Implementations must write atomically and never throw to callers. */
    void save(BotMemory memory);

    /** Deletes all stored memory. */
    void clear();

    /** A store that keeps everything in memory, for tests and for a disabled persistence setting. */
    static MemoryStore inMemory() {
        return new MemoryStore() {
            private BotMemory memory;

            @Override
            public Optional<BotMemory> load() {
                return Optional.ofNullable(memory);
            }

            @Override
            public void save(BotMemory value) {
                this.memory = value;
            }

            @Override
            public void clear() {
                this.memory = null;
            }
        };
    }
}
