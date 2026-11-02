package com.logvex.addon.chatlog.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Compressed, on-disk chat log store.
 *
 * <p>Entries are appended to a bounded in-memory buffer and flushed to a gzip-compressed JSONL
 * segment when the buffer fills or a flush is requested. Only {@link LogSegmentMeta} records are
 * kept in memory; entry bodies are decompressed on demand. All mutations are serialised through
 * {@link #lock} and every file is written atomically (temp file + move), so an interrupted write
 * can never corrupt an existing segment.
 */
public final class LogStore {
    private static final String INDEX_FILE = "index.json";
    private static final String SEGMENT_SUFFIX = ".log.gz";
    private static final int INDEX_VERSION = 1;

    /** Entries buffered before an automatic flush. */
    public static final int DEFAULT_FLUSH_THRESHOLD = 250;

    private final Path root;
    private final Object lock = new Object();
    private final List<LogSegmentMeta> index = new CopyOnWriteArrayList<>();
    private final List<ChatEntry> pending = new ArrayList<>();

    private int flushThreshold = DEFAULT_FLUSH_THRESHOLD;
    private int compressionLevel = 6;
    private volatile boolean loaded;

    public LogStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public void setFlushThreshold(int threshold) {
        this.flushThreshold = Math.max(1, threshold);
    }

    public void setCompressionLevel(int level) {
        this.compressionLevel = Math.min(9, Math.max(1, level));
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Loads the segment index, rebuilding it from the files when it is missing or unreadable. */
    public void load() throws IOException {
        synchronized (lock) {
            Files.createDirectories(root);
            index.clear();

            Path indexPath = root.resolve(INDEX_FILE);
            boolean fromIndex = false;
            if (Files.exists(indexPath)) {
                try {
                    List<String> entries = Json.toStringList(Files.readString(indexPath, StandardCharsets.UTF_8));
                    for (String entry : entries) {
                        LogSegmentMeta meta = LogSegmentMeta.fromMap(Json.toMap(entry));
                        if (meta != null && meta.fileName() != null && !meta.fileName().isEmpty()) index.add(meta);
                    }
                    fromIndex = true;
                } catch (RuntimeException e) {
                    index.clear();
                    fromIndex = false;
                }
            }

            if (!fromIndex) rebuildIndexLocked();
            loaded = true;
        }
    }

    /** Scans the log directory and rebuilds the index from readable segments. */
    public void rebuildIndex() throws IOException {
        synchronized (lock) {
            rebuildIndexLocked();
        }
    }

    private void rebuildIndexLocked() throws IOException {
        index.clear();
        if (!Files.isDirectory(root)) return;

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(SEGMENT_SUFFIX))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path file : files) {
            try {
                LogSegmentMeta meta = readMetaLocked(file);
                if (meta != null) index.add(meta);
            } catch (RuntimeException ignored) {
                // Unreadable segment: it is simply not listed.
            }
        }
        sortOldestFirst();
        persistIndexLocked();
    }

    /** Buffers an entry, flushing automatically once the threshold is reached. */
    public void append(ChatEntry entry) throws IOException {
        if (entry == null) return;
        List<ChatEntry> toWrite = null;
        synchronized (lock) {
            pending.add(entry);
            if (pending.size() >= flushThreshold) {
                toWrite = new ArrayList<>(pending);
                pending.clear();
            }
        }
        if (toWrite != null) flushEntries(toWrite);
    }

    /** Writes everything currently buffered. */
    public void flush() throws IOException {
        List<ChatEntry> toWrite;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            toWrite = new ArrayList<>(pending);
            pending.clear();
        }
        flushEntries(toWrite);
    }

    private void flushEntries(List<ChatEntry> entries) throws IOException {
        if (entries.isEmpty()) return;
        synchronized (lock) {
            Files.createDirectories(root);
            long first = entries.get(0).timestamp();
            long last = entries.get(entries.size() - 1).timestamp();
            String session = entries.get(0).sessionId();
            String server = entries.get(0).server();

            byte[] encoded = LogCodec.encode(entries, compressionLevel);
            String fileName = segmentName(first, session);
            Path target = root.resolve(fileName);

            writeAtomic(target, encoded);

            long uncompressed = estimateUncompressed(entries);
            index.add(new LogSegmentMeta(fileName, session, server, first, last, entries.size(),
                encoded.length, uncompressed));
            sortOldestFirst();
            persistIndexLocked();
        }
    }

    private void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = root.resolve(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void persistIndexLocked() throws IOException {
        Path indexPath = root.resolve(INDEX_FILE);
        Path tmp = root.resolve(INDEX_FILE + ".tmp");

        List<String> encoded = new ArrayList<>(index.size());
        for (LogSegmentMeta meta : index) encoded.add(Json.toJson(meta.toMap()));

        Files.writeString(tmp, Json.toJson(encoded), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ queries

    /**
     * Streams every entry, oldest first, to {@code visitor}. Segments are decoded one at a time so a
     * large archive never has to be resident in memory at once. {@code visitor} may return false to
     * stop early.
     */
    public void forEach(EntryVisitor visitor) throws IOException {
        flush();
        List<LogSegmentMeta> snapshot = new ArrayList<>(index);
        snapshot.sort(Comparator.comparingLong(LogSegmentMeta::firstTimestamp));
        for (LogSegmentMeta meta : snapshot) {
            if (!visitSegment(meta, visitor)) return;
        }
    }

    /** Streams matching entries newest first. */
    public List<ChatEntry> query(LogQuery query, int limit) throws IOException {
        flush();
        List<LogSegmentMeta> snapshot = new ArrayList<>(index);
        snapshot.sort(Comparator.comparingLong(LogSegmentMeta::lastTimestamp).reversed());

        List<ChatEntry> results = new ArrayList<>();
        if (limit <= 0) limit = Integer.MAX_VALUE;

        for (LogSegmentMeta meta : snapshot) {
            if (query != null && !query.matchesSegment(meta)) continue;
            List<ChatEntry> entries;
            try {
                entries = readSegment(meta);
            } catch (ChatLogCorruptedException e) {
                continue;
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                ChatEntry entry = entries.get(i);
                if (query == null || query.matches(entry)) {
                    results.add(entry);
                    if (results.size() >= limit) return results;
                }
            }
        }
        return results;
    }

    /** Streams matching entries, newest first, without materialising the whole result set. */
    public void search(LogQuery query, EntryVisitor visitor) throws IOException {
        flush();
        List<LogSegmentMeta> snapshot = new ArrayList<>(index);
        snapshot.sort(Comparator.comparingLong(LogSegmentMeta::lastTimestamp).reversed());

        for (LogSegmentMeta meta : snapshot) {
            if (query != null && !query.matchesSegment(meta)) continue;
            List<ChatEntry> entries;
            try {
                entries = readSegment(meta);
            } catch (ChatLogCorruptedException e) {
                continue;
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                ChatEntry entry = entries.get(i);
                if (query == null || query.matches(entry)) {
                    if (!visitor.visit(entry)) return;
                }
            }
        }
    }

    private boolean visitSegment(LogSegmentMeta meta, EntryVisitor visitor) throws IOException {
        List<ChatEntry> entries;
        try {
            entries = readSegment(meta);
        } catch (ChatLogCorruptedException e) {
            return true;
        }
        for (ChatEntry entry : entries) {
            if (!visitor.visit(entry)) return false;
        }
        return true;
    }

    /** Reads and validates one segment. */
    public List<ChatEntry> readSegment(LogSegmentMeta meta) throws IOException, ChatLogCorruptedException {
        Path file = root.resolve(meta.fileName());
        if (!Files.exists(file)) throw new ChatLogCorruptedException("Segment file is missing: " + meta.fileName());
        return LogCodec.decode(Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ maintenance

    /** Removes expired segments. Keeps at least one segment so the archive is never wiped by age. */
    public int cleanupByAge(long maxAgeMs, long nowMs) throws IOException {
        if (maxAgeMs <= 0) return 0;
        synchronized (lock) {
            List<LogSegmentMeta> ordered = new ArrayList<>(index);
            ordered.sort(Comparator.comparingLong(LogSegmentMeta::lastTimestamp));
            int removed = 0;
            for (LogSegmentMeta meta : ordered) {
                if (index.size() <= 1) break;
                if (nowMs - meta.lastTimestamp() > maxAgeMs) {
                    deleteSegmentLocked(meta);
                    removed++;
                }
            }
            if (removed > 0) persistIndexLocked();
            return removed;
        }
    }

    /** Removes the oldest segments until the total compressed size fits in {@code maxBytes}. */
    public int cleanupBySize(long maxBytes) throws IOException {
        if (maxBytes <= 0) return 0;
        synchronized (lock) {
            List<LogSegmentMeta> ordered = new ArrayList<>(index);
            ordered.sort(Comparator.comparingLong(LogSegmentMeta::lastTimestamp));
            int removed = 0;
            while (totalCompressedBytesLocked() > maxBytes && ordered.size() > 1) {
                deleteSegmentLocked(ordered.remove(0));
                removed++;
            }
            if (removed > 0) persistIndexLocked();
            return removed;
        }
    }

    /** Removes the oldest segments until at most {@code maxSegments} remain. */
    public int cleanupByCount(int maxSegments) throws IOException {
        if (maxSegments <= 0) return 0;
        synchronized (lock) {
            List<LogSegmentMeta> ordered = new ArrayList<>(index);
            ordered.sort(Comparator.comparingLong(LogSegmentMeta::lastTimestamp));
            int removed = 0;
            while (index.size() > maxSegments && ordered.size() > 1) {
                deleteSegmentLocked(ordered.remove(0));
                removed++;
            }
            if (removed > 0) persistIndexLocked();
            return removed;
        }
    }

    /** Applies every retention limit at once. */
    public int cleanup(int maxSegments, long maxBytes, long maxAgeMs, long nowMs) throws IOException {
        int removed = 0;
        removed += cleanupByAge(maxAgeMs, nowMs);
        removed += cleanupBySize(maxBytes);
        removed += cleanupByCount(maxSegments);
        return removed;
    }

    /** Deletes every segment. Buffered entries are dropped as well. */
    public int deleteAll() throws IOException {
        synchronized (lock) {
            pending.clear();
            int removed = index.size();
            for (LogSegmentMeta meta : new ArrayList<>(index)) deleteSegmentLocked(meta);
            index.clear();
            persistIndexLocked();
            return removed;
        }
    }

    /** Deletes one segment by file name. */
    public boolean deleteSegment(String fileName) throws IOException {
        synchronized (lock) {
            for (LogSegmentMeta meta : new ArrayList<>(index)) {
                if (meta.fileName().equals(fileName)) {
                    deleteSegmentLocked(meta);
                    index.remove(meta);
                    persistIndexLocked();
                    return true;
                }
            }
            return false;
        }
    }

    private void deleteSegmentLocked(LogSegmentMeta meta) throws IOException {
        Files.deleteIfExists(root.resolve(meta.fileName()));
        Files.deleteIfExists(root.resolve(meta.fileName() + ".tmp"));
        index.remove(meta);
    }

    // ------------------------------------------------------------------ stats

    public List<LogSegmentMeta> segments() {
        return List.copyOf(index);
    }

    public int segmentCount() {
        return index.size();
    }

    public long totalEntries() {
        long total = 0;
        for (LogSegmentMeta meta : index) total += meta.count();
        return total;
    }

    public long totalCompressedBytes() {
        synchronized (lock) {
            return totalCompressedBytesLocked();
        }
    }

    private long totalCompressedBytesLocked() {
        long total = 0;
        for (LogSegmentMeta meta : index) total += meta.compressedSize();
        return total;
    }

    public long totalUncompressedBytes() {
        long total = 0;
        for (LogSegmentMeta meta : index) total += meta.uncompressedSize();
        return total;
    }

    public long oldestTimestamp() {
        long oldest = Long.MAX_VALUE;
        for (LogSegmentMeta meta : index) oldest = Math.min(oldest, meta.firstTimestamp());
        return oldest == Long.MAX_VALUE ? 0 : oldest;
    }

    public long newestTimestamp() {
        long newest = 0;
        for (LogSegmentMeta meta : index) newest = Math.max(newest, meta.lastTimestamp());
        return newest;
    }

    // ------------------------------------------------------------------ helpers

    private LogSegmentMeta readMetaLocked(Path file) {
        try {
            List<ChatEntry> entries = LogCodec.decode(Files.readAllBytes(file));
            if (entries.isEmpty()) return null;
            long first = entries.get(0).timestamp();
            long last = entries.get(entries.size() - 1).timestamp();
            return new LogSegmentMeta(file.getFileName().toString(), entries.get(0).sessionId(),
                entries.get(0).server(), first, last, entries.size(),
                Files.size(file), estimateUncompressed(entries));
        } catch (IOException | ChatLogCorruptedException | RuntimeException e) {
            return null;
        }
    }

    private void sortOldestFirst() {
        index.sort(Comparator.comparingLong(LogSegmentMeta::firstTimestamp));
    }

    private String segmentName(long firstTimestamp, String sessionId) {
        String session = sessionId == null || sessionId.isBlank() ? "session" : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return firstTimestamp + "-" + session + SEGMENT_SUFFIX;
    }

    static long estimateUncompressed(List<ChatEntry> entries) {
        long total = 0;
        for (ChatEntry entry : entries) {
            total += entry.content().getBytes(StandardCharsets.UTF_8).length + entry.sender().length() + 64L;
        }
        return total;
    }

    /** Visitor used by the streaming read paths. Returning false stops the walk. */
    public interface EntryVisitor {
        boolean visit(ChatEntry entry);
    }
}
