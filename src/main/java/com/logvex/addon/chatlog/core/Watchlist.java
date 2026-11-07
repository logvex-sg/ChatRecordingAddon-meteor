package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small, case-insensitive list of player names the user asked to watch.
 *
 * <p>Used to decide which joins, leaves and renames are worth a louder notice. It is deliberately
 * separate from the activity exclude list: excluding a player keeps them out of the index, while
 * watching a player raises their events to a notification. The list is bounded and de-duplicated.
 */
public final class Watchlist {
    /** Upper bound on watched names, so a runaway config cannot grow it without limit. */
    public static final int MAX_ENTRIES = 500;

    private final Set<String> names = new LinkedHashSet<>();

    public synchronized void set(List<String> values) {
        names.clear();
        if (values == null) return;
        for (String value : values) {
            if (names.size() >= MAX_ENTRIES) break;
            add(value);
        }
    }

    /** Adds a name; returns false when it was blank or already present. */
    public synchronized boolean add(String name) {
        if (name == null || name.isBlank() || names.size() >= MAX_ENTRIES) return false;
        return names.add(name.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean remove(String name) {
        return name != null && names.remove(name.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean contains(String name) {
        return name != null && names.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean isEmpty() {
        return names.isEmpty();
    }

    public synchronized int size() {
        return names.size();
    }

    /** The watched names, in insertion order, as originally cased where possible. */
    public synchronized List<String> entries() {
        return new ArrayList<>(names);
    }

    public synchronized void clear() {
        names.clear();
    }
}
