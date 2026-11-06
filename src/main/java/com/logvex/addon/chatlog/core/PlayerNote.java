package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A user-written note attached to a player.
 *
 * <p>Notes are the one place the addon stores something a client cannot observe, so they are
 * explicit user input rather than inferred data: a note is only ever created, edited or removed by
 * the user. They persist with the module's settings.
 */
public final class PlayerNote {
    private final String player;
    private String text;
    private long updatedAt;

    public PlayerNote(String player, String text, long updatedAt) {
        this.player = player == null ? "" : player.trim();
        this.text = text == null ? "" : text;
        this.updatedAt = updatedAt;
    }

    public String player() {
        return player;
    }

    public String text() {
        return text;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void setText(String text, long now) {
        this.text = text == null ? "" : text;
        this.updatedAt = now;
    }

    /** True when the note's player or body matches {@code filter} (case-insensitive). */
    public boolean matches(String filter) {
        if (filter == null || filter.isBlank()) return true;
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        return player.toLowerCase(Locale.ROOT).contains(needle)
            || text.toLowerCase(Locale.ROOT).contains(needle);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("player", player);
        map.put("text", text);
        map.put("updated", updatedAt);
        return map;
    }

    public static PlayerNote fromMap(Map<?, ?> map) {
        if (map == null) return null;
        String player = str(map.get("player"));
        if (player.isBlank()) return null;
        return new PlayerNote(player, str(map.get("text")), num(map.get("updated")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** A case-insensitive player-name index, capped so notes cannot grow without bound. */
    public static final class Store {
        public static final int DEFAULT_CAPACITY = 1000;

        private final int capacity;
        private final Map<String, PlayerNote> byPlayer = new LinkedHashMap<>();

        public Store() {
            this(DEFAULT_CAPACITY);
        }

        public Store(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        /** Adds or replaces a note. Returns the stored note, or null for a blank player name. */
        public synchronized PlayerNote put(String player, String text, long now) {
            if (player == null || player.isBlank()) return null;
            String key = player.trim().toLowerCase(Locale.ROOT);
            PlayerNote note = byPlayer.get(key);
            if (note == null) {
                note = new PlayerNote(player.trim(), text, now);
                byPlayer.put(key, note);
                trim();
            } else {
                note.setText(text, now);
            }
            return note;
        }

        public synchronized PlayerNote get(String player) {
            if (player == null) return null;
            return byPlayer.get(player.trim().toLowerCase(Locale.ROOT));
        }

        public synchronized boolean remove(String player) {
            if (player == null) return false;
            return byPlayer.remove(player.trim().toLowerCase(Locale.ROOT)) != null;
        }

        /** Every note matching {@code filter}, newest first. */
        public synchronized List<PlayerNote> search(String filter) {
            List<PlayerNote> out = new ArrayList<>();
            for (PlayerNote note : byPlayer.values()) {
                if (note.matches(filter)) out.add(note);
            }
            out.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
            return out;
        }

        public synchronized int size() {
            return byPlayer.size();
        }

        public synchronized void clear() {
            byPlayer.clear();
        }

        private void trim() {
            while (byPlayer.size() > capacity) {
                String oldest = byPlayer.keySet().iterator().next();
                byPlayer.remove(oldest);
            }
        }
    }
}
