package com.logvex.addon.chatrelay.mc;

import com.logvex.addon.LogvexAddon;
import com.logvex.addon.chatrelay.core.ArchiveCorruptedException;
import com.logvex.addon.chatrelay.core.ChatReport;
import com.logvex.addon.chatrelay.core.ReportArchive;
import com.logvex.addon.chatrelay.core.ReportMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Runs every archive operation on a background worker and delivers results back on the client
 * thread, so no disk I/O or decompression ever runs inside a render or tick callback.
 */
public final class ArchiveService {
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-chatrelay-archive");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ReportArchive archive;
    private volatile List<ReportMetadata> cache = List.of();

    public ArchiveService(Path root) {
        this.archive = new ReportArchive(root);
    }

    public Path root() {
        return archive.root();
    }

    public void setRoot(Path root, Runnable onLoaded) {
        ReportArchive next = new ReportArchive(root);
        io.execute(() -> {
            try {
                next.load();
                archive = next;
                cache = next.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Failed to load chat report archive at {}", root, e);
            }
            onClient(onLoaded);
        });
    }

    public void loadIndexAsync(Runnable onLoaded) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                current.load();
                cache = current.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Failed to load chat report archive", e);
            }
            onClient(onLoaded);
        });
    }

    /** Cached metadata, safe to read from the render thread. */
    public List<ReportMetadata> metadata() {
        return cache;
    }

    public int count() {
        return cache.size();
    }

    public long totalCompressedBytes() {
        long total = 0;
        for (ReportMetadata metadata : cache) total += metadata.compressedSize();
        return total;
    }

    public long totalUncompressedBytes() {
        long total = 0;
        for (ReportMetadata metadata : cache) total += metadata.uncompressedSize();
        return total;
    }

    public void writeAsync(ChatReport report, int level, Consumer<ReportMetadata> onSuccess, Consumer<String> onError) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                ReportMetadata metadata = current.write(report, level);
                cache = current.all();
                onClient(() -> onSuccess.accept(metadata));
            } catch (Exception e) {
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    public void loadReportAsync(String id, Consumer<ChatReport> onSuccess, Consumer<String> onError) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                ChatReport report = current.load(id);
                onClient(() -> onSuccess.accept(report));
            } catch (IOException | ArchiveCorruptedException e) {
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    public void searchAsync(String id, String query, Consumer<List<com.logvex.addon.chatrelay.core.ChatMessage>> onSuccess, Consumer<String> onError) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                List<com.logvex.addon.chatrelay.core.ChatMessage> matches = current.search(id, query);
                onClient(() -> onSuccess.accept(matches));
            } catch (IOException | ArchiveCorruptedException e) {
                onClient(() -> onError.accept(describe(e)));
            }
        });
    }

    public void deleteAsync(String id, Runnable onDone) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                current.delete(id);
                cache = current.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Failed to delete chat report {}", id, e);
            }
            onClient(onDone);
        });
    }

    public void deleteAllAsync(Runnable onDone) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                current.deleteAll();
                cache = current.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Failed to clear the chat report archive", e);
            }
            onClient(onDone);
        });
    }

    public void cleanupAsync(int maxReports, long maxBytes, long maxAgeMs, Runnable onDone) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                current.cleanup(maxReports, maxBytes, maxAgeMs, System.currentTimeMillis());
                cache = current.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Chat report cleanup failed", e);
            }
            onClient(onDone);
        });
    }

    public void updateStatusAsync(ReportMetadata metadata, Runnable onDone) {
        ReportArchive current = archive;
        io.execute(() -> {
            try {
                current.updateMetadata(metadata);
                cache = current.all();
            } catch (IOException e) {
                LogvexAddon.LOG.error("Failed to update chat report metadata {}", metadata.id(), e);
            }
            onClient(onDone);
        });
    }

    public void shutdown() {
        io.shutdownNow();
    }

    private void onClient(Runnable task) {
        if (task == null) return;
        if (mc == null) {
            task.run();
            return;
        }
        mc.execute(task);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
