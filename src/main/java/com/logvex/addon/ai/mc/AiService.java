package com.logvex.addon.ai.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logvex.addon.ai.core.AiEngine;
import com.logvex.addon.framework.LogClientContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The process-wide local AI: one {@link AiEngine} plus its JSON backing.
 *
 * <p>The engine itself is cheap enough to run on the game thread, so this service does not exist to
 * move work off it — it exists to keep disk I/O off it. Loading and saving run on a private daemon
 * thread, writes coalesce, and a failed save degrades to "the AI forgets what it learned" rather
 * than stalling a tick. That mirrors {@code AssistantService}, which backs the memory.
 */
public final class AiService {
    private static final AiService INSTANCE = new AiService();

    public static AiService get() {
        return INSTANCE;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-ai-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final AiEngine engine = new AiEngine();
    private volatile Path file = defaultPath();
    private volatile boolean loaded;
    private volatile String lastError = "";

    private AiService() {
    }

    public AiEngine engine() {
        return engine;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String lastError() {
        return lastError;
    }

    public Path file() {
        return file;
    }

    /** Redirects persistence to {@code path}; used by tests so they never touch the game directory. */
    public void setFile(Path file) {
        this.file = file;
    }

    /** Loads the persisted model, then runs {@code onLoaded} on the client thread. */
    public void loadAsync(Runnable onLoaded) {
        io.execute(() -> {
            readFromDisk();
            loaded = true;
            if (onLoaded == null) return;
            if (LogClientContext.mc == null) onLoaded.run();
            else LogClientContext.mc.execute(onLoaded);
        });
    }

    /** Queues an atomic write; repeat calls coalesce into one pending write. */
    public void saveAsync() {
        if (!loaded) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        // Snapshot on the caller's thread, which owns the engine, before handing it to the writer.
        JsonObject snapshot = engine.toJson();
        io.execute(() -> {
            saveQueued.set(false);
            writeToDisk(snapshot);
        });
    }

    /** Flushes and stops the writer. Called once at shutdown. */
    public void shutdown() {
        if (loaded) {
            JsonObject snapshot = engine.toJson();
            io.execute(() -> writeToDisk(snapshot));
        }
        io.shutdown();
    }

    private void readFromDisk() {
        Path target = file;
        if (target == null || !Files.exists(target)) return;
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            engine.loadJson(root);
            lastError = "";
        } catch (Exception e) {
            // A corrupt or partial file must never stop the addon: the built-in model still works.
            lastError = "Could not read the stored AI model: " + e.getMessage();
        }
    }

    private void writeToDisk(JsonObject snapshot) {
        Path target = file;
        if (target == null) return;
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastError = "";
        } catch (IOException e) {
            lastError = "Could not save the AI model: " + e.getMessage();
        }
    }

    /** The on-disk location: {@code <game-dir>/config/logclient/assistant-ai.json}. */
    public static Path defaultPath() {
        return LogClientContext.FOLDER().toPath().resolve("logclient").resolve("assistant-ai.json");
    }
}
