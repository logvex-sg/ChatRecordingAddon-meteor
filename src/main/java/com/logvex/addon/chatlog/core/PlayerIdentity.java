package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names one account has been observed using.
 *
 * <p>A client cannot see an account's rename history; it can only notice that the same UUID is
 * later announced under a different name. This record keeps exactly that: the ordered list of
 * distinct names observed for one {@link #uuid()}, each with the time it was last seen. When the
 * name changes, the previous name is retained so the change can be reported rather than silently
 * overwriting the old one.
 *
 * <p>Pure data with no Minecraft types, so it can be unit tested and serialised directly.
 */
public final class PlayerIdentity {
    /** One observed name and when it was last seen for this account. */
    public record NameChange(String name, long observedAt) {
    }

    private final String uuid;
    private final List<NameChange> names = new ArrayList<>();
    private long firstSeen;
    private long lastSeen;

    public PlayerIdentity(String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    public String uuid() {
        return uuid;
    }

    /** The most recently observed name, or an empty string when nothing has been seen yet. */
    public String currentName() {
        return names.isEmpty() ? "" : names.get(names.size() - 1).name();
    }

    /** The name observed before the current one, or an empty string when there was none. */
    public String previousName() {
        return names.size() < 2 ? "" : names.get(names.size() - 2).name();
    }

    /** Every distinct name observed, oldest first. */
    public List<NameChange> names() {
        return List.copyOf(names);
    }

    /** How many distinct names have been observed. */
    public int nameCount() {
        return names.size();
    }

    /** How many renames were observed (distinct names minus one, never below zero). */
    public int changeCount() {
        return Math.max(0, names.size() - 1);
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** When the current name was first observed, or 0 when nothing has been seen. */
    public long currentNameSince() {
        return names.isEmpty() ? 0 : names.get(names.size() - 1).observedAt();
    }

    /**
     * Records an observation of {@code name} at {@code atMs}.
     *
     * @return true when this was a rename (the name differs from the current one)
     */
    public boolean observe(String name, long atMs) {
        if (name == null || name.isBlank()) return false;
        String trimmed = name.trim();

        if (firstSeen == 0 || atMs < firstSeen) firstSeen = atMs;
        if (atMs > lastSeen) lastSeen = atMs;

        if (names.isEmpty()) {
            names.add(new NameChange(trimmed, atMs));
            return false;
        }

        NameChange current = names.get(names.size() - 1);
        if (current.name().equals(trimmed)) {
            // Same name: refresh the timestamp in place without recording a spurious change.
            names.set(names.size() - 1, new NameChange(trimmed, atMs));
            return false;
        }

        names.add(new NameChange(trimmed, atMs));
        return true;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", uuid);
        map.put("current", currentName());
        map.put("first", firstSeen);
        map.put("last", lastSeen);
        map.put("names", names.size());
        return map;
    }
}
