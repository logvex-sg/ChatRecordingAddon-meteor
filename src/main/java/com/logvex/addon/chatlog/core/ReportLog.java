package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A bounded history of reports this client filed.
 *
 * <p>Until this existed a report left no trace once it was handed to the webhook: the queue kept
 * counters, the delivery log kept hosts and outcomes, but nothing recorded <em>what</em> was
 * reported. That made the common follow-up question - "did I already report that griefer, and
 * when?" - unanswerable without scrolling Discord.
 *
 * <p>Records are immutable and carry the outcome as it was known at the time. A report that failed
 * to deliver stays in the log, marked failed, because a report the user believes was filed but was
 * not is exactly the case worth keeping.
 */
public final class ReportLog {
    public static final int DEFAULT_CAPACITY = 0;

    /** One filed report and what became of it. */
    public record Record(long atMs, String reporter, String reason, String kind, boolean delivered,
                         String note) {
        public Record {
            reporter = reporter == null ? "" : reporter;
            reason = reason == null ? "" : reason;
            kind = kind == null || kind.isBlank() ? "report" : kind;
            note = note == null ? "" : note;
        }

        /** True when this was a request for a moderator rather than a report about someone. */
        public boolean moderatorRequest() {
            return "mod".equalsIgnoreCase(kind);
        }

        /** {@code 14:05 mod Alex: reason}, for a one-line list. */
        public String oneLine() {
            return ReportFormatting.shortTime(atMs) + " " + kind + " " + reporter + ": " + reason;
        }
    }

    private final Deque<Record> records = new ArrayDeque<>();
    private int capacity;

    public ReportLog() {
        this(DEFAULT_CAPACITY);
    }

    public ReportLog(int capacity) {
        this.capacity = Math.max(0, capacity);
    }

    /** The maximum number of records kept. */
    public synchronized int capacity() {
        return capacity;
    }

    /** Changes the cap, dropping the oldest records when it shrinks. 0 means no cap. */
    public synchronized void setCapacity(int newCapacity) {
        this.capacity = Math.max(0, newCapacity);
        trim();
    }

    private void trim() {
        if (capacity <= 0) return;
        while (records.size() > capacity) records.removeLast();
    }

    /** Appends a record, dropping the oldest when full. */
    public synchronized void add(Record record) {
        if (record == null) return;
        records.addFirst(record);
        trim();
    }

    /** The newest records first. */
    public synchronized List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        for (Record record : records) {
            if (out.size() >= Math.max(0, limit)) break;
            out.add(record);
        }
        return out;
    }

    /** Every record, newest first. */
    public synchronized List<Record> all() {
        return new ArrayList<>(records);
    }

    /** Records whose reporter or reason contains {@code filter}, newest first. */
    public synchronized List<Record> search(String filter) {
        if (filter == null || filter.isBlank()) return all();
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        List<Record> out = new ArrayList<>();
        for (Record record : records) {
            if (record.reporter().toLowerCase(Locale.ROOT).contains(needle)
                || record.reason().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * Replaces the outcome of {@code target} once delivery is known.
     *
     * <p>A report is written when it is queued, before delivery has happened, and updated here when
     * the network worker reports back. Without this a queued-but-failed report would sit in the log
     * marked delivered, which is the one lie a report log must not tell.
     *
     * <p>Matched by identity rather than by timestamp: two reports filed in the same millisecond
     * would otherwise settle each other.
     */
    public synchronized boolean settle(Record target, boolean delivered, String note) {
        if (target == null) return false;
        List<Record> ordered = new ArrayList<>(records);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) != target) continue;
            Record old = ordered.get(i);
            ordered.set(i, new Record(old.atMs(), old.reporter(), old.reason(), old.kind(),
                delivered, note));
            records.clear();
            records.addAll(ordered);
            return true;
        }
        return false;
    }

    /** How many records were filed by {@code reporter}. */
    public synchronized int countBy(String reporter) {
        if (reporter == null || reporter.isBlank()) return 0;
        String who = reporter.strip();
        int n = 0;
        for (Record record : records) {
            if (record.reporter().equalsIgnoreCase(who)) n++;
        }
        return n;
    }

    /** How many records failed to deliver. */
    public synchronized int failures() {
        int n = 0;
        for (Record record : records) if (!record.delivered()) n++;
        return n;
    }

    /** The most recent record filed by {@code reporter}, or null. */
    public synchronized Record lastBy(String reporter) {
        if (reporter == null || reporter.isBlank()) return null;
        String who = reporter.strip();
        for (Record record : records) {
            if (record.reporter().equalsIgnoreCase(who)) return record;
        }
        return null;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized boolean isEmpty() {
        return records.isEmpty();
    }

    public synchronized void clear() {
        records.clear();
    }

    /** One record per line, newest first, for the setting that persists this log. */
    public synchronized List<String> encodeAll() {
        List<String> out = new ArrayList<>();
        for (Record record : records) out.add(encode(record));
        return out;
    }

    /** Replaces the contents from {@link #encodeAll()} output, newest first. */
    public synchronized void loadAll(List<String> encoded) {
        records.clear();
        if (encoded == null) return;
        // Decoded newest-first like the encoding, so a reload preserves the original order.
        for (String line : encoded) {
            Record record = decode(line);
            if (record != null) records.addLast(record);
        }
        trim();
    }

    private static final String SEP = "\u0001";

    /** Encodes one record so it survives a round trip through the settings file. */
    static String encode(Record record) {
        return record.atMs() + SEP + record.kind() + SEP + record.delivered() + SEP
            + escape(record.reporter()) + SEP + escape(record.reason()) + SEP + escape(record.note());
    }

    /** Decodes one line, or null when it is not a record this class wrote. */
    static Record decode(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split(SEP, 6);
        if (parts.length < 6) return null;
        try {
            return new Record(Long.parseLong(parts[0]), unescape(parts[3]), unescape(parts[4]),
                parts[1], Boolean.parseBoolean(parts[2]), unescape(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // The separators are control characters that never appear in chat, but a reason could still
    // contain a backslash; escaping keeps the round trip lossless.
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace(SEP, " ");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
