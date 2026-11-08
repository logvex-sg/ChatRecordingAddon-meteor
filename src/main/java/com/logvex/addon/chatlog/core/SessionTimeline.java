package com.logvex.addon.chatlog.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A bounded, chronological list of session milestones.
 *
 * <p>Only events the client can actually observe are recorded: joins, leaves, deaths, keyword hits,
 * session start/end and notable status changes. The list is capped so a long session cannot grow it
 * without bound; the oldest entries are dropped first.
 */
public final class SessionTimeline {
    /** The event categories a milestone can carry. */
    public enum Kind {
        SESSION_START,
        SESSION_END,
        JOIN,
        LEAVE,
        DEATH,
        KEYWORD,
        MILESTONE,
        WARNING;

        public static Kind parse(String value, Kind fallback) {
            if (value == null) return fallback;
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(value.trim())) return kind;
            }
            return fallback;
        }

        /** A short, stable label for the GUI. */
        public String label() {
            return switch (this) {
                case SESSION_START -> "Session start";
                case SESSION_END -> "Session end";
                case JOIN -> "Join";
                case LEAVE -> "Leave";
                case DEATH -> "Death";
                case KEYWORD -> "Keyword";
                case MILESTONE -> "Milestone";
                case WARNING -> "Warning";
            };
        }
    }

    /** One milestone. */
    public record Event(long timestamp, Kind kind, String subject, String detail) {
        public Event {
            if (kind == null) kind = Kind.MILESTONE;
            subject = subject == null ? "" : subject;
            detail = detail == null ? "" : detail;
        }

        /** A single readable line, e.g. {@code Join: Notch}. */
        public String line() {
            if (subject.isEmpty()) return detail.isEmpty() ? kind.label() : detail;
            return detail.isEmpty() ? kind.label() + ": " + subject
                : kind.label() + ": " + subject + " (" + detail + ")";
        }
    }

    public static final int DEFAULT_CAPACITY = 500;

    private int capacity;
    private final Deque<Event> events = new ArrayDeque<>();

    public SessionTimeline() {
        this(DEFAULT_CAPACITY);
    }

    public SessionTimeline(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Changes the cap, dropping the oldest milestones when the new cap is smaller. */
    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        while (events.size() > this.capacity) events.removeFirst();
    }

    public synchronized int capacity() {
        return capacity;
    }

    public synchronized void add(long timestamp, Kind kind, String subject, String detail) {
        events.addLast(new Event(timestamp, kind, subject, detail));
        while (events.size() > capacity) events.removeFirst();
    }

    /** Records a milestone derived from a chat entry, or does nothing for an ordinary message. */
    public synchronized void observe(ChatEntry entry) {
        if (entry == null) return;
        switch (entry.type()) {
            case JOIN -> add(entry.timestamp(), Kind.JOIN, entry.sender(), "");
            case LEAVE -> add(entry.timestamp(), Kind.LEAVE, entry.sender(), "");
            case DEATH -> add(entry.timestamp(), Kind.DEATH, entry.sender(), "");
            default -> {
                // Ordinary messages are not milestones.
            }
        }
    }

    /** Every event, oldest first. */
    public synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    /** The most recent {@code limit} events, newest first. */
    public synchronized List<Event> recent(int limit) {
        List<Event> all = new ArrayList<>(events);
        List<Event> out = new ArrayList<>(Math.min(limit, all.size()));
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) out.add(all.get(i));
        return out;
    }

    /** Events whose subject, detail or kind label contains {@code filter}, newest first. */
    public synchronized List<Event> search(String filter) {
        if (filter == null || filter.isBlank()) return recent(capacity);
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        List<Event> out = new ArrayList<>();
        List<Event> all = new ArrayList<>(events);
        for (int i = all.size() - 1; i >= 0; i--) {
            Event event = all.get(i);
            if (event.subject().toLowerCase(Locale.ROOT).contains(needle)
                || event.detail().toLowerCase(Locale.ROOT).contains(needle)
                || event.kind().name().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(event);
            }
        }
        return out;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized long count(Kind kind) {
        long total = 0;
        for (Event event : events) {
            if (event.kind() == kind) total++;
        }
        return total;
    }

    public synchronized void clear() {
        events.clear();
    }
}
