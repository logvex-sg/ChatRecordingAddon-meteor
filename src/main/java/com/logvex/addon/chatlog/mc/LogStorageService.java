package com.logvex.addon.chatlog.mc;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.ChatLogCorruptedException;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogImporter;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.LogSegmentMeta;
import com.logvex.addon.chatlog.core.LogStore;
import com.logvex.addon.chatlog.core.PlayerTracker;
import com.logvex.addon.chatlog.core.PlayerSessionTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs every chat-log disk operation on a background worker and delivers results back on the client
 * thread, so no file I/O, compression or decompression ever runs inside a render or tick callback.
 *
 * <p>The in-memory index ({@link LogStore#segments()}) is kept current after every mutation and can
 * be read directly from the render thread; only the entry bodies require the worker.
 */
public final class LogStorageService {
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-chatlog-io");
        thread.setDaemon(true);
        return thread;
    });

    private volatile LogStore store;
    private volatile String lastError = "";

    public LogStorageService(Path root) {
        this.store = new LogStore(root);
    }

    public Path root() {
        return store.root();
    }

    public LogStore store() {
        return store;
    }

    public String lastError() {
        return lastError;
    }

    public List<LogSegmentMeta> segments() {
        return store.segments();
    }

    /** Loads the index in the background, then runs {@code onLoaded} on the client thread. */
    public void loadAsync(Runnable onLoaded) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.load();
            } catch (IOException e) {
                fail("Failed to load the chat log store", e);
            }
            onClient(onLoaded);
        });
    }

    /** Points the service at a new directory and reloads. */
    public void setRootAsync(Path root, Runnable onLoaded) {
        LogStore next = new LogStore(root);
        next.setCompressionLevel(store.compressionLevel());
        io.execute(() -> {
            try {
                next.load();
                store = next;
            } catch (IOException e) {
                fail("Failed to open the chat log store at " + root, e);
            }
            onClient(onLoaded);
        });
    }

    public void setCompressionLevel(int level) {
        store.setCompressionLevel(level);
    }

    public void setFlushThreshold(int threshold) {
        store.setFlushThreshold(threshold);
    }

    /** Appends and flushes an entry off the client thread. */
    public void appendAsync(ChatEntry entry) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.append(entry);
            } catch (IOException e) {
                fail("Failed to write a chat log entry", e);
            }
        });
    }

    public void flushAsync(Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.flush();
            } catch (IOException e) {
                fail("Failed to flush the chat log store", e);
            }
            onClient(onDone);
        });
    }

    /** Streams matching entries to {@code consumer} on the client thread, in chunks. */
    public void searchAsync(LogQuery query, Consumer<List<ChatEntry>> onChunk, Consumer<Integer> onDone) {
        LogStore current = store;
        io.execute(() -> {
            int[] count = {0};
            try {
                current.search(query, entry -> {
                    count[0]++;
                    onClient(() -> onChunk.accept(List.of(entry)));
                    return true;
                });
            } catch (IOException e) {
                fail("Failed to search the chat log store", e);
            }
            onClient(() -> onDone.accept(count[0]));
        });
    }

    /** Collects up to {@code limit} matching entries and delivers them once, on the client thread. */
    public void searchListAsync(LogQuery query, int limit, Consumer<List<ChatEntry>> onResult, Consumer<Integer> onDone) {
        LogStore current = store;
        io.execute(() -> {
            List<ChatEntry> results;
            try {
                results = current.query(query, limit);
            } catch (IOException e) {
                fail("Failed to search the chat log store", e);
                results = List.of();
            }
            List<ChatEntry> out = results;
            onClient(() -> {
                onResult.accept(out);
                onDone.accept(out.size());
            });
        });
    }

    /** Reads one segment fully on the worker thread. */
    public void readSegmentAsync(LogSegmentMeta meta, Consumer<List<ChatEntry>> onSuccess, Consumer<String> onError) {
        LogStore current = store;
        io.execute(() -> {
            try {
                List<ChatEntry> entries = current.readSegment(meta);
                onClient(() -> onSuccess.accept(entries));
            } catch (IOException | ChatLogCorruptedException e) {
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    public void deleteSegmentAsync(String fileName, Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.deleteSegment(fileName);
            } catch (IOException e) {
                fail("Failed to delete a chat log segment", e);
            }
            onClient(onDone);
        });
    }

    public void deleteAllAsync(Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.deleteAll();
            } catch (IOException e) {
                fail("Failed to clear the chat log store", e);
            }
            onClient(onDone);
        });
    }

    public void cleanupAsync(int maxSegments, long maxBytes, long maxAgeMs, Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.cleanup(maxSegments, maxBytes, maxAgeMs, System.currentTimeMillis());
            } catch (IOException e) {
                fail("Chat log cleanup failed", e);
            }
            onClient(onDone);
        });
    }

    public void rebuildIndexAsync(Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            try {
                current.rebuildIndex();
            } catch (IOException e) {
                fail("Failed to rebuild the chat log index", e);
            }
            onClient(onDone);
        });
    }

    /**
     * Exports matching entries to {@code destination}. The query is re-run on the worker thread and
     * streamed into the file, so a large export never blocks or buffers in memory.
     */
    public void exportAsync(LogQuery query, Path destination, LogExporter.Format format,
                            Consumer<Integer> onSuccess, Consumer<String> onError) {
        LogStore current = store;
        io.execute(() -> {
            LogExporter exporter = new LogExporter(format);
            try {
                exporter.open(destination);
                current.search(query, entry -> {
                    try {
                        exporter.write(entry);
                    } catch (IOException e) {
                        throw new ExportFailure(e);
                    }
                    return true;
                });
                exporter.close();
                int written = exporter.written();
                onClient(() -> onSuccess.accept(written));
            } catch (ExportFailure e) {
                exporter.abort();
                onClient(() -> onError.accept(describe(e.getCause())));
            } catch (IOException e) {
                exporter.abort();
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    /** Rebuilds the player activity index from the stored logs. */
    public void rebuildPlayersAsync(PlayerTracker tracker, Runnable onDone) {
        rebuildPlayersAsync(tracker, null, onDone);
    }

    /**
     * Rebuilds the player activity index, and optionally the session tracker, from the stored logs.
     * Both are fed in one pass so a large archive is read once, not twice.
     */
    public void rebuildPlayersAsync(PlayerTracker tracker, PlayerSessionTracker sessions, Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            if (tracker != null) tracker.clear();
            if (sessions != null) sessions.clear();
            try {
                current.forEach(entry -> {
                    if (tracker != null) tracker.record(entry);
                    if (sessions != null) sessions.record(entry);
                    return true;
                });
            } catch (IOException e) {
                fail("Failed to rebuild player activity", e);
            }
            onClient(onDone);
        });
    }

    public void shutdown() {
        io.shutdownNow();
    }

    /**
     * Parses a log file in the background and delivers the result on the client thread.
     *
     * <p>The file can be a native {@code .log.gz} segment, JSON lines, CSV or the plain text export.
     * Parsing is pure Java and runs on the worker, so a large file never stalls the game.
     */
    public void importLogAsync(Path path, Consumer<LogImporter.Result> onSuccess, Consumer<String> onError) {
        io.execute(() -> {
            try {
                LogImporter.Result result = LogImporter.read(path);
                onClient(() -> onSuccess.accept(result));
            } catch (IOException | RuntimeException e) {
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    /** Merges imported entries into the store, in order, off the client thread. */
    public void importEntriesAsync(List<ChatEntry> entries, Runnable onDone) {
        LogStore current = store;
        io.execute(() -> {
            int appended = 0;
            try {
                for (ChatEntry entry : entries) {
                    current.append(entry);
                    appended++;
                }
            } catch (IOException e) {
                fail("Imported " + appended + " of " + entries.size() + " entries", e);
            }
            onClient(onDone);
        });
    }

    private void onClient(Runnable task) {
        if (task == null) return;
        if (com.logvex.addon.framework.MeteorClient.mc == null) {
            task.run();
            return;
        }
        com.logvex.addon.framework.MeteorClient.mc.execute(task);
    }

    private void fail(String what, Exception e) {
        lastError = what + ": " + describe(e);
        LogAddon.LOG.error(lastError, e);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** Wraps an IOException raised inside a streaming callback. */
    private static final class ExportFailure extends RuntimeException {
        ExportFailure(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
