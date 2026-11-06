package com.logvex.addon.chatlog.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads chat entries back from a file.
 *
 * <p>Every format the addon can export is importable: the native compressed store segment
 * ({@code .log.gz}, which is CRC-checked through {@link LogCodec}), JSON lines, CSV and the plain
 * text export. Anything it cannot parse is reported as a skipped line rather than silently dropped,
 * so a partial import is always visible to the user.
 *
 * <p>The parser is pure Java and never touches Minecraft, so it is unit tested directly. A caller
 * that must not block the client thread streams lines through {@link #parseLine} itself.
 */
public final class LogImporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    /** A single line that could not be understood, with the reason shown to the user. */
    public record SkippedLine(int lineNumber, String raw, String reason) {
        @Override
        public String toString() {
            return "line " + lineNumber + ": " + reason;
        }
    }

    /** The result of reading a file. */
    public record Result(
        List<ChatEntry> entries,
        List<SkippedLine> skipped,
        String format
    ) {
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** A short description of the import, phrased for chat. */
        public String describe(String fileName) {
            String invalid = skipped.isEmpty()
                ? "no skipped lines"
                : com.logvex.addon.common.Messages.plural(skipped.size(), "skipped line", "skipped lines");
            return com.logvex.addon.common.Messages.format(
                "Imported %s from %s as %s (%s).",
                com.logvex.addon.common.Messages.plural(entries.size(), "message"),
                fileName, format, invalid);
        }
    }

    private static final Pattern TEXT_LINE =
        Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]\\s*([^:]{1,32}):\\s?(.*)$");

    private LogImporter() {
    }

    /** Detects the format from the file name and parses it. */
    public static Result read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("That file does not exist.");
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".log.gz") || name.endsWith(".gz")) {
            return readNative(path);
        }
        return readText(path);
    }

    private static Result readNative(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        try {
            List<ChatEntry> entries = LogCodec.decode(raw);
            return new Result(List.copyOf(entries), List.of(), "native log");
        } catch (ChatLogCorruptedException e) {
            throw new IOException("The file is not a readable addon log: " + e.getMessage(), e);
        }
    }

    private static Result readText(Path path) throws IOException {
        List<ChatEntry> entries = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        String format = "text";

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            boolean headerChecked = false;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;

                if (!headerChecked) {
                    headerChecked = true;
                    if (line.startsWith("timestamp,")) {
                        format = "csv";
                        continue;
                    }
                    if (line.startsWith("{")) format = "json";
                }

                ChatEntry entry = parseLine(line, format);
                if (entry == null) {
                    skipped.add(new SkippedLine(lineNumber, line,
                        format.equals("text") ? "not a recognized log line" : "not a valid " + format + " record"));
                    continue;
                }
                entries.add(entry);
            }
        }
        return new Result(List.copyOf(entries), List.copyOf(skipped), format);
    }

    /** Parses one line in the given format. Returns {@code null} when the line is not a valid record. */
    public static ChatEntry parseLine(String line, String format) {
        if (line == null || line.isBlank()) return null;
        return switch (format) {
            case "json" -> fromJson(line);
            case "csv" -> fromCsv(line);
            default -> fromText(line);
        };
    }

    private static ChatEntry fromJson(String line) {
        Map<?, ?> map = Json.toMap(line);
        return ChatEntry.fromMap(map);
    }

    private static ChatEntry fromText(String line) {
        Matcher matcher = TEXT_LINE.matcher(line);
        if (!matcher.matches()) return null;
        long timestamp = parseTime(matcher.group(1));
        if (timestamp < 0) return null;
        String label = matcher.group(2).trim();
        String content = matcher.group(3);

        // The exporter writes the sender when there is one and the message type otherwise, so a label
        // that is not a known type is treated as a sender.
        MessageType type = typeFromLabel(label);
        String sender = type == null ? label : "";
        return new ChatEntry(timestamp, type == null ? MessageType.PUBLIC_CHAT : type, sender, content, "", "");
    }

    private static ChatEntry fromCsv(String line) {
        List<String> cells = splitCsv(line);
        if (cells.size() < 5) return null;
        long timestamp;
        try {
            timestamp = Long.parseLong(cells.get(0).trim());
        } catch (NumberFormatException e) {
            timestamp = parseTime(cells.get(1).trim());
        }
        if (timestamp < 0) return null;
        return new ChatEntry(timestamp, MessageType.fromString(cells.get(2)), cells.get(3), cells.get(4),
            cells.size() > 5 ? cells.get(5) : "", cells.size() > 6 ? cells.get(6) : "");
    }

    private static MessageType typeFromLabel(String label) {
        for (MessageType type : MessageType.values()) {
            if (type.toString().equalsIgnoreCase(label)) return type;
        }
        return null;
    }

    private static long parseTime(String text) {
        try {
            LocalDateTime local = LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Splits a CSV row honouring double-quoted fields with doubled quotes. */
    public static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>(8);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    /** Formats a timestamp for a message, used by the preview screen. */
    public static String formatTime(long epochMillis) {
        return STAMP.format(Instant.ofEpochMilli(epochMillis));
    }
}
