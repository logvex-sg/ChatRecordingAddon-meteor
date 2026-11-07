package com.logvex.addon.world.core;

/**
 * Everything remembered about one world or server, kept together so unrelated worlds never mix.
 *
 * <p>A profile is keyed by a stable {@link #key()} derived from the server address and the world
 * name (or the singleplayer world name), so a player's base on one server never appears on another.
 * Minecraft-free; the caller supplies the key.
 */
public final class WorldProfile {
    private final String key;
    private String label;

    private final WorldDatabase locations = new WorldDatabase();
    private final DiscoveryLog discoveries = new DiscoveryLog();
    private final CountIndex biomes = new CountIndex();
    private final CountIndex resources = new CountIndex();
    private final VisitedChunks chunks = new VisitedChunks();

    private long firstSeen;
    private long lastSeen;

    public WorldProfile(String key, String label) {
        this.key = key == null ? "" : key;
        this.label = label == null ? "" : label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public WorldProfile label(String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    public WorldDatabase locations() {
        return locations;
    }

    public DiscoveryLog discoveries() {
        return discoveries;
    }

    public CountIndex biomes() {
        return biomes;
    }

    public CountIndex resources() {
        return resources;
    }

    public VisitedChunks chunks() {
        return chunks;
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void touch(long now) {
        if (firstSeen == 0) firstSeen = now;
        lastSeen = now;
    }

    public void setSeen(long first, long last) {
        this.firstSeen = first;
        this.lastSeen = last;
    }

    /** A one-line summary for the GUI and the map header. */
    public String summary() {
        return locations.size() + " place(s), " + biomes.size() + " biome(s), "
            + resources.size() + " resource type(s), " + chunks.size() + " chunk(s)";
    }

    /**
     * Builds a stable profile key from a server address and a world/dimension name.
     *
     * <p>Singleplayer uses the literal {@code singleplayer} in place of an address, and the world
     * name distinguishes saves, so two singleplayer worlds do not share a profile.
     */
    public static String buildKey(String serverAddress, String worldName) {
        String server = serverAddress == null || serverAddress.isBlank() ? "singleplayer" : serverAddress.trim();
        String world = worldName == null || worldName.isBlank() ? "default" : worldName.trim();
        return (server + "|" + world).toLowerCase(java.util.Locale.ROOT);
    }
}
