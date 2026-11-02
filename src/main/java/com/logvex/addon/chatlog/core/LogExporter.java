package com.logvex.addon.chatlog.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes chat entries to disk in a text format.
 *
 * <p>Writes are buffered and the destination is written through a temp file that is moved into
 * place, so an export interrupted halfway never replaces a good file with a partial one. The
 * caller streams entries in, which keeps memory flat for large exports.
 */
public final class LogExporter {
    public enum Format {
        TXT,
        JSON,
        CSV;

        public String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final Format format;
    private BufferedWriter writer;
    private Path target;
    private Path temp;
    private int written;
    private boolean headerWritten;

    public LogExporter(Format format) {
        this.format = format;
    }

    /** Opens the destination. Any existing file is only replaced once {@link #close()} succeeds. */
    public void open(Path destination) throws IOException {
        this.target = destination;
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.temp = destination.resolveSibling(destination.getFileName() + ".part");
        this.writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
    }

    public void write(ChatEntry entry) throws IOException {
        if (writer == null || entry == null) return;
        switch (format) {
            case TXT -> writer.write(ReportFormatting.format(entry));
            case JSON -> writer.write(Json.toJson(entry.toMap()));
            case CSV -> {
                if (!headerWritten) {
                    writer.write("timestamp,iso_time,type,sender,content,server,session");
                    writer.newLine();
                    headerWritten = true;
                }
                writer.write(csvRow(entry));
            }
        }
        writer.newLine();
        written++;
    }

    public void writeAll(List<ChatEntry> entries) throws IOException {
        for (ChatEntry entry : entries) write(entry);
    }

    public int written() {
        return written;
    }

    /** Flushes, closes and atomically publishes the file. */
    public void close() throws IOException {
        if (writer == null) return;
        writer.flush();
        writer.close();
        writer = null;
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Aborts the export, discarding the partial file. */
    public void abort() {
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the partial file is removed below.
        }
        writer = null;
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private static String csvRow(ChatEntry entry) {
        return String.join(",",
            Long.toString(entry.timestamp()),
            quote(STAMP.format(Instant.ofEpochMilli(entry.timestamp()))),
            quote(entry.type().id()),
            quote(entry.sender()),
            quote(entry.content()),
            quote(entry.server()),
            quote(entry.sessionId()));
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
