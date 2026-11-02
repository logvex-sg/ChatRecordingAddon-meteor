package com.logvex.addon.chatlog.core;

import java.util.Locale;
import java.util.Set;

/**
 * Immutable filter for log queries. A {@code null} field means "no restriction".
 *
 * <p>{@link #matchesSegment} only compares timestamp ranges, which lets the store skip whole
 * segments without decompressing them — the reason searching a large archive stays cheap.
 */
public final class LogQuery {
    private final String text;
    private final String player;
    private final Set<MessageType> types;
    private final long fromInclusive;
    private final long toInclusive;
    private final String sessionId;
    private final String server;

    private LogQuery(Builder builder) {
        this.text = normalise(builder.text);
        this.player = normalise(builder.player);
        this.types = builder.types == null || builder.types.isEmpty() ? null : Set.copyOf(builder.types);
        this.fromInclusive = builder.fromInclusive;
        this.toInclusive = builder.toInclusive;
        this.sessionId = blankToNull(builder.sessionId);
        this.server = blankToNull(builder.server);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An unrestricted query. */
    public static LogQuery all() {
        return builder().build();
    }

    public boolean hasText() {
        return text != null;
    }

    public String text() {
        return text;
    }

    public String player() {
        return player;
    }

    /** True when a segment with this timestamp range could contain a match. */
    public boolean matchesSegment(LogSegmentMeta meta) {
        if (meta == null) return false;
        if (toInclusive > 0 && meta.firstTimestamp() > toInclusive) return false;
        if (fromInclusive > 0 && meta.lastTimestamp() < fromInclusive) return false;
        if (sessionId != null && !sessionId.equals(meta.sessionId())) return false;
        if (server != null && !server.equalsIgnoreCase(meta.server())) return false;
        return true;
    }

    public boolean matches(ChatEntry entry) {
        if (entry == null) return false;
        if (fromInclusive > 0 && entry.timestamp() < fromInclusive) return false;
        if (toInclusive > 0 && entry.timestamp() > toInclusive) return false;
        if (types != null && !types.contains(entry.type())) return false;
        if (sessionId != null && !sessionId.equals(entry.sessionId())) return false;
        if (server != null && !server.equalsIgnoreCase(entry.server())) return false;
        if (player != null && !entry.sender().toLowerCase(Locale.ROOT).contains(player)) return false;
        if (text != null && !entry.matches(text)) return false;
        return true;
    }

    private static String normalise(String value) {
        if (value == null) return null;
        String trimmed = value.strip().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private String text;
        private String player;
        private Set<MessageType> types;
        private long fromInclusive;
        private long toInclusive;
        private String sessionId;
        private String server;

        public Builder text(String value) {
            this.text = value;
            return this;
        }

        public Builder player(String value) {
            this.player = value;
            return this;
        }

        public Builder types(Set<MessageType> value) {
            this.types = value;
            return this;
        }

        public Builder from(long timestampInclusive) {
            this.fromInclusive = timestampInclusive;
            return this;
        }

        public Builder to(long timestampInclusive) {
            this.toInclusive = timestampInclusive;
            return this;
        }

        public Builder session(String value) {
            this.sessionId = value;
            return this;
        }

        public Builder server(String value) {
            this.server = value;
            return this;
        }

        public LogQuery build() {
            return new LogQuery(this);
        }
    }
}
