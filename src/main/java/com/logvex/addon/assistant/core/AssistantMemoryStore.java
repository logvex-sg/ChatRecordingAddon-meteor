package com.logvex.addon.assistant.core;

import java.util.Optional;

/** Persistence for {@link AssistantMemory}. The Minecraft layer supplies a file-backed store. */
public interface AssistantMemoryStore {
    /** Loads the stored memory, or empty when nothing valid is stored yet. */
    Optional<AssistantMemory> load();

    /** Persists the memory. Implementations must write atomically and never throw to callers. */
    void save(AssistantMemory memory);

    /** Deletes all stored memory. */
    void clear();

    /** A store that keeps everything in memory, for tests and for a disabled persistence setting. */
    static AssistantMemoryStore inMemory() {
        return new AssistantMemoryStore() {
            private AssistantMemory memory;

            @Override
            public Optional<AssistantMemory> load() {
                return Optional.ofNullable(memory);
            }

            @Override
            public void save(AssistantMemory value) {
                this.memory = value;
            }

            @Override
            public void clear() {
                this.memory = null;
            }
        };
    }
}
