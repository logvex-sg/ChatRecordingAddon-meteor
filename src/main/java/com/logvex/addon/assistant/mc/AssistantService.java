package com.logvex.addon.assistant.mc;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantMemoryStore;
import com.logvex.addon.framework.Addon;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The process-wide assistant memory: one {@link AssistantMemory} plus its file backing.
 *
 * <p>Load and save run on a private daemon thread so neither ever blocks the client thread. Writes
 * coalesce: many quick edits produce a single pending write, and the final state is always what
 * lands on disk. This mirrors the pattern the player-identity index uses, so a failure to persist
 * degrades to "the assistant forgets on restart" rather than a stalled game.
 */
public final class AssistantService {
    private static final AssistantService INSTANCE = new AssistantService();

    public static AssistantService get() {
        return INSTANCE;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-assistant-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean saveQueued = new AtomicBoolean();

    private volatile AssistantMemoryStore store = new FileAssistantStore(FileAssistantStore.defaultPath());
    private final AssistantMemory memory = new AssistantMemory();
    private volatile boolean loaded;
    private volatile String lastError = "";

    private AssistantService() {
    }

    public AssistantMemory memory() {
        return memory;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String lastError() {
        return lastError;
    }

    /** Redirects persistence to {@code path}; used by tests so they never touch the game directory. */
    public void setFile(Path path) {
        this.store = new FileAssistantStore(path);
    }

    /** Replaces the store (tests, or a disabled persistence setting). */
    public void setStore(AssistantMemoryStore store) {
        this.store = store == null ? AssistantMemoryStore.inMemory() : store;
    }

    /** Loads the persisted memory, then runs {@code onLoaded} on the client thread. */
    public void loadAsync(Runnable onLoaded) {
        io.execute(() -> {
            Optional<AssistantMemory> stored = store.load();
            if (stored.isPresent()) {
                replaceWith(stored.get());
            } else if (store instanceof FileAssistantStore file) {
                lastError = file.lastError();
            }
            loaded = true;
            if (onLoaded == null) return;
            if (Addon.mc == null) onLoaded.run();
            else Addon.mc.execute(onLoaded);
        });
    }

    /** Queues an atomic write; repeat calls coalesce into one pending write. */
    public void saveAsync() {
        if (!loaded) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        // Snapshot on the caller's thread, which owns the memory, before handing it to the writer.
        AssistantMemory snapshot = memory.copy();
        io.execute(() -> {
            saveQueued.set(false);
            store.save(snapshot);
            if (store instanceof FileAssistantStore file) lastError = file.lastError();
        });
    }

    /** Flushes the memory and stops the writer. Called once at shutdown. */
    public void shutdown() {
        if (loaded) {
            AssistantMemory snapshot = memory.copy();
            io.execute(() -> store.save(snapshot));
        }
        io.shutdown();
    }

    /** Clears the persisted and in-memory memory. */
    public void clear() {
        memory.clear();
        io.execute(store::clear);
    }

    /** Copies the contents of {@code source} into the live memory instance. */
    private void replaceWith(AssistantMemory source) {
        memory.clear();
        for (AssistantMemory.Fact fact : source.facts()) {
            memory.rememberFact(fact.key(), fact.value(), fact.updatedAt());
        }
        for (String player : source.knownPlayers()) {
            for (AssistantMemory.Fact fact : source.playerFacts(player)) {
                memory.rememberPlayerFact(player, fact.key(), fact.value(), fact.updatedAt());
            }
        }
        for (AssistantMemory.ConversationEntry entry : source.allHistory()) {
            memory.recordConversation(entry.player(), entry.question(), entry.answer(), entry.timestamp());
        }
        for (var counter : source.counters().entrySet()) {
            memory.incrementCounter(counter.getKey(), counter.getValue());
        }
    }
}
