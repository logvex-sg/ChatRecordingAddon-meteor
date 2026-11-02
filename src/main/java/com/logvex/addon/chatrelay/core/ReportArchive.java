package com.logvex.addon.chatrelay.core;

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
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compressed, on-disk report archive.
 *
 * <p>Only {@link ReportMetadata} is held in memory. Message bodies are stored as gzip-compressed
 * JSONL and are decompressed on demand by {@link #load(String)}. Every mutation is serialised
 * through {@link #writeLock} and written atomically (temp file + move), so a crash mid-write can
 * never leave a half-written report behind.
 */
public final class ReportArchive {
    private static final String INDEX_FILE = "index.json";
    private static final String ENTRY_SUFFIX = ".jsonl.gz";
    private static final int INDEX_VERSION = 1;

    private final Path root;
    private final List<ReportMetadata> index = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public ReportArchive(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** Loads the index, repairing it from the report files when it is missing or unreadable. */
    public void load() throws IOException {
        synchronized (writeLock) {
            Files.createDirectories(root);
            index.clear();

            Path indexPath = root.resolve(INDEX_FILE);
            boolean loadedFromIndex = false;

            if (Files.exists(indexPath)) {
                try {
                    String json = Files.readString(indexPath, StandardCharsets.UTF_8);
                    List<String> entries = Json.toStringList(json);
                    for (String entry : entries) {
                        java.util.Map<?, ?> map = Json.toMap(entry);
                        if (map == null) continue;
                        ReportMetadata metadata = ReportMetadata.fromMap(map);
                        if (metadata.id() != null && !metadata.id().isEmpty()) index.add(metadata);
                    }
                    loadedFromIndex = true;
                } catch (Exception e) {
                    index.clear();
                    loadedFromIndex = false;
                }
            }

            if (!loadedFromIndex) rebuildIndex();
            loaded.set(true);
        }
    }

    /** Scans the archive directory and rebuilds the index from whatever files are readable. */
    public void rebuildIndex() throws IOException {
        index.clear();
        if (!Files.isDirectory(root)) return;

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(ENTRY_SUFFIX))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path file : files) {
            try {
                ReportMetadata metadata = readMetadata(file);
                if (metadata != null) index.add(metadata);
            } catch (Exception ignored) {
                // Unreadable file: skip it, the index simply will not list it.
            }
        }
        sortNewestFirst();
    }

    /**
     * Writes a report to the archive. The entry is written before the index is updated, so a
     * failure here never removes anything that already exists.
     */
    public ReportMetadata write(ChatReport report, int compressionLevel) throws IOException {
        synchronized (writeLock) {
            Files.createDirectories(root);

            byte[] encoded = ArchiveCodec.encode(report.messages(), compressionLevel);
            String fileName = safeFileName(report.id()) + ENTRY_SUFFIX;
            Path target = root.resolve(fileName);

            long uncompressed = estimateUncompressed(report);
            ReportMetadata metadata = new ReportMetadata(
                report.id(),
                report.serverAddress(),
                report.startTime(),
                report.endTime(),
                report.generatedAt(),
                report.messageCount(),
                report.deliveryStatus(),
                encoded.length,
                uncompressed,
                fileName
            );

            writeEntry(target, encoded);
            upsertIndexEntry(metadata);
            return metadata;
        }
    }

    private void writeEntry(Path target, byte[] encoded) throws IOException {
        Path tmp = root.resolve(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, encoded, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
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

    /** Replaces the metadata of an existing report, e.g. after its delivery status changed. */
    public void updateMetadata(ReportMetadata metadata) throws IOException {
        synchronized (writeLock) {
            upsertIndexEntry(metadata);
        }
    }

    private void upsertIndexEntry(ReportMetadata metadata) throws IOException {
        index.removeIf(existing -> existing.id().equals(metadata.id()));
        index.add(metadata);
        sortNewestFirst();
        persistIndex();
    }

    /** Decompresses one report. The entry is validated before it is parsed. */
    public ChatReport load(String id) throws IOException, ArchiveCorruptedException {
        ReportMetadata metadata = metadata(id);
        if (metadata == null) throw new IOException("Unknown report: " + id);

        Path file = root.resolve(metadata.fileName());
        if (!Files.exists(file)) {
            removeFromIndex(id);
            throw new ArchiveCorruptedException("Archive file is missing: " + metadata.fileName());
        }

        byte[] raw = Files.readAllBytes(file);
        List<ChatMessage> messages = ArchiveCodec.decode(raw);

        return new ChatReport(
            metadata.id(),
            metadata.serverAddress(),
            metadata.startTime(),
            metadata.endTime(),
            metadata.generatedAt(),
            metadata.deliveryStatus(),
            messages
        );
    }

    public boolean delete(String id) throws IOException {
        synchronized (writeLock) {
            ReportMetadata metadata = metadata(id);
            if (metadata == null) return false;

            Files.deleteIfExists(root.resolve(metadata.fileName()));
            Files.deleteIfExists(root.resolve(metadata.fileName() + ".tmp"));
            removeFromIndex(id);
            return true;
        }
    }

    public int deleteAll() throws IOException {
        synchronized (writeLock) {
            int removed = index.size();
            for (ReportMetadata metadata : new ArrayList<>(index)) {
                Files.deleteIfExists(root.resolve(metadata.fileName()));
                Files.deleteIfExists(root.resolve(metadata.fileName() + ".tmp"));
            }
            index.clear();
            persistIndex();
            return removed;
        }
    }

    private void removeFromIndex(String id) throws IOException {
        index.removeIf(existing -> existing.id().equals(id));
        persistIndex();
    }

    private void persistIndex() throws IOException {
        Path indexPath = root.resolve(INDEX_FILE);
        Path tmp = root.resolve(INDEX_FILE + ".tmp");

        List<String> encoded = new ArrayList<>(index.size());
        for (ReportMetadata metadata : index) encoded.add(Json.toJson(metadata.toMap()));

        Files.writeString(tmp, Json.toJson(encoded), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        try {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Enforces the retention limits. Reports are removed oldest first until every limit is
     * satisfied. At least one report is always kept.
     *
     * @return the number of reports removed
     */
    public int cleanup(int maxReports, long maxTotalBytes, long maxAgeMs, long nowMs) throws IOException {
        synchronized (writeLock) {
            List<ReportMetadata> ordered = new ArrayList<>(index);
            ordered.sort(Comparator.comparingLong(ReportMetadata::generatedAt));

            long total = 0;
            for (ReportMetadata metadata : index) total += metadata.compressedSize();

            List<ReportMetadata> toDelete = new ArrayList<>();
            int remaining = ordered.size();

            for (ReportMetadata metadata : ordered) {
                if (remaining <= 1) break;

                boolean tooOld = maxAgeMs > 0 && metadata.generatedAt() > 0
                    && nowMs - metadata.generatedAt() > maxAgeMs;
                boolean tooMany = maxReports > 0 && remaining > maxReports;
                boolean tooBig = maxTotalBytes > 0 && total > maxTotalBytes;

                if (tooOld || tooMany || tooBig) {
                    toDelete.add(metadata);
                    total -= metadata.compressedSize();
                    remaining--;
                }
            }

            for (ReportMetadata metadata : toDelete) {
                Files.deleteIfExists(root.resolve(metadata.fileName()));
                index.remove(metadata);
            }

            if (!toDelete.isEmpty()) persistIndex();
            return toDelete.size();
        }
    }

    /** Case-insensitive substring search over the message bodies of one report. */
    public List<ChatMessage> search(String id, String query) throws IOException, ArchiveCorruptedException {
        ChatReport report = load(id);
        if (query == null || query.isBlank()) return report.messages();

        String needle = query.toLowerCase(Locale.ROOT);
        List<ChatMessage> matches = new ArrayList<>();
        for (ChatMessage message : report.messages()) {
            if (message.content().toLowerCase(Locale.ROOT).contains(needle)
                || message.sender().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(message);
            }
        }
        return matches;
    }

    public List<ReportMetadata> all() {
        return List.copyOf(index);
    }

    public ReportMetadata metadata(String id) {
        for (ReportMetadata metadata : index) {
            if (metadata.id().equals(id)) return metadata;
        }
        return null;
    }

    public int size() {
        return index.size();
    }

    public long totalCompressedBytes() {
        long total = 0;
        for (ReportMetadata metadata : index) total += metadata.compressedSize();
        return total;
    }

    public long totalUncompressedBytes() {
        long total = 0;
        for (ReportMetadata metadata : index) total += metadata.uncompressedSize();
        return total;
    }

    public boolean isLoaded() {
        return loaded.get();
    }

    private void sortNewestFirst() {
        index.sort(Comparator.comparingLong(ReportMetadata::generatedAt).reversed());
    }

    private ReportMetadata readMetadata(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        List<ChatMessage> messages;
        try {
            messages = ArchiveCodec.decode(raw);
        } catch (ArchiveCorruptedException e) {
            return null;
        }

        String id = file.getFileName().toString().replace(ENTRY_SUFFIX, "");
        long start = 0;
        long end = 0;
        if (!messages.isEmpty()) {
            start = messages.get(0).timestamp();
            end = messages.get(messages.size() - 1).timestamp();
        }

        return new ReportMetadata(id, "", start, end, 0, messages.size(),
            ChatReport.STATUS_LOCAL, raw.length, estimateUncompressed(messages), file.getFileName().toString());
    }

    private static long estimateUncompressed(ChatReport report) {
        return estimateUncompressed(report.messages());
    }

    private static long estimateUncompressed(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) {
            total += message.content().getBytes(StandardCharsets.UTF_8).length + 48L;
        }
        return total;
    }

    private static String safeFileName(String id) {
        String cleaned = id == null ? "report" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "report" : cleaned;
    }
}
