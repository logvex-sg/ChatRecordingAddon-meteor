package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tracks the observed names of the accounts the client has seen.
 *
 * <p>A rename is only ever inferred from a name difference for the same UUID in the server's player
 * list. The tracker never guesses: without a UUID it records nothing, so it cannot mislabel two
 * different players as one account. The map is bounded, so a long-running client on a busy network
 * cannot grow it without limit.
 *
 * <p>Thread-safe: the packet thread records while the GUI thread reads snapshots.
 */
public final class PlayerIdentityTracker {
    /** How many distinct accounts to keep before the least recently seen is dropped. */
    public static final int MAX_ACCOUNTS = 2000;

    /** One rename worth reporting: the account, its old name and its new one. */
    public record Rename(String uuid, String from, String to, long atMs) {
    }

    private final Map<String, PlayerIdentity> byUuid = new LinkedHashMap<>();
    private final List<Rename> renames = new ArrayList<>();
    private final int maxRenames;

    public PlayerIdentityTracker() {
        this(500);
    }

    public PlayerIdentityTracker(int maxRenames) {
        this.maxRenames = Math.max(1, maxRenames);
    }

    /**
     * Records that {@code uuid} is known as {@code name}.
     *
     * @return the rename that this observation revealed, or null when there was none
     */
    public synchronized Rename observe(String uuid, String name, long atMs) {
        if (uuid == null || uuid.isBlank() || name == null || name.isBlank()) return null;

        PlayerIdentity identity = byUuid.get(uuid);
        if (identity == null) {
            identity = new PlayerIdentity(uuid);
            byUuid.put(uuid, identity);
            evictIfNeeded();
        }

        String before = identity.currentName();
        boolean renamed = identity.observe(name, atMs);
        if (!renamed) return null;

        Rename rename = new Rename(uuid, before, name, atMs);
        renames.add(rename);
        while (renames.size() > maxRenames) renames.remove(0);
        return rename;
    }

    public synchronized PlayerIdentity get(String uuid) {
        return byUuid.get(uuid);
    }

    /** The identity for a current name, or null when that name was never observed with a UUID. */
    public synchronized PlayerIdentity byName(String name) {
        if (name == null) return null;
        for (PlayerIdentity identity : byUuid.values()) {
            if (identity.currentName().equalsIgnoreCase(name)) return identity;
        }
        return null;
    }

    public synchronized int size() {
        return byUuid.size();
    }

    /** Accounts whose current or previous name contains {@code filter}, most recently seen first. */
    public synchronized List<PlayerIdentity> search(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<PlayerIdentity> out = new ArrayList<>();
        for (PlayerIdentity identity : byUuid.values()) {
            if (needle.isEmpty() || contains(identity, needle)) out.add(identity);
        }
        out.sort(Comparator.comparingLong(PlayerIdentity::lastSeen).reversed());
        return out;
    }

    /** Only the accounts that have been observed under more than one name. */
    public synchronized List<PlayerIdentity> renamed() {
        List<PlayerIdentity> out = new ArrayList<>();
        for (PlayerIdentity identity : byUuid.values()) {
            if (identity.changeCount() > 0) out.add(identity);
        }
        out.sort(Comparator.comparingLong(PlayerIdentity::lastSeen).reversed());
        return out;
    }

    /** Every observed rename, newest first. */
    public synchronized List<Rename> renames() {
        List<Rename> out = new ArrayList<>(renames);
        out.sort(Comparator.comparingLong(Rename::atMs).reversed());
        return out;
    }

    public synchronized int renameCount() {
        return renames.size();
    }

    public synchronized boolean forget(String uuid) {
        return uuid != null && byUuid.remove(uuid) != null;
    }

    public synchronized void clear() {
        byUuid.clear();
        renames.clear();
    }

    /** The full identities as plain maps, for persistence. */
    public synchronized List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>(byUuid.size());
        for (PlayerIdentity identity : byUuid.values()) out.add(identity.toFullMap());
        return out;
    }

    /**
     * Replaces the tracker with {@code loaded}, rebuilding the rename log from each identity's
     * observed names so a restored index reports the same history it had before.
     */
    public synchronized void load(List<PlayerIdentity> loaded) {
        byUuid.clear();
        renames.clear();
        if (loaded == null) return;
        for (PlayerIdentity identity : loaded) {
            if (identity == null || identity.uuid().isBlank() || identity.nameCount() == 0) continue;
            byUuid.put(identity.uuid(), identity);
        }
        rebuildRenames();
        evictIfNeeded();
    }

    private void rebuildRenames() {
        for (PlayerIdentity identity : byUuid.values()) {
            List<PlayerIdentity.NameChange> names = identity.names();
            for (int i = 1; i < names.size(); i++) {
                renames.add(new Rename(identity.uuid(), names.get(i - 1).name(), names.get(i).name(),
                    names.get(i).observedAt()));
            }
        }
        renames.sort(Comparator.comparingLong(Rename::atMs));
        while (renames.size() > maxRenames) renames.remove(0);
    }

    private static boolean contains(PlayerIdentity identity, String needle) {
        for (PlayerIdentity.NameChange change : identity.names()) {
            if (change.name().toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private void evictIfNeeded() {
        while (byUuid.size() > MAX_ACCOUNTS) {
            String oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            for (Map.Entry<String, PlayerIdentity> entry : byUuid.entrySet()) {
                if (entry.getValue().lastSeen() < oldestSeen) {
                    oldestSeen = entry.getValue().lastSeen();
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) return;
            byUuid.remove(oldest);
        }
    }
}
