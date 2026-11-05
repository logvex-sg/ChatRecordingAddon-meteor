package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bot's persistent knowledge: waypoints, home, storage locations, resource locations, completed
 * and failed objectives, and the task history summary.
 *
 * <p>This class is pure data with explicit versioning. {@link #CURRENT_VERSION} is bumped whenever
 * the on-disk shape changes and {@link #migrate(Map)} upgrades older maps in place, so a stored
 * memory from an older build is upgraded rather than discarded. The Minecraft-free design lets the
 * memory be unit tested and lets {@link MemoryStore} implementations stay trivial.
 */
public final class BotMemory {
    /** On-disk schema version. Bump on any incompatible change and add a migration step. */
    public static final int CURRENT_VERSION = 1;

    /** A named point in the world. */
    public static final class Waypoint {
        private String name;
        private String dimension;
        private int x;
        private int y;
        private int z;
        private String note = "";
        private long createdAt;

        public Waypoint(String name, String dimension, int x, int y, int z) {
            this.name = name == null ? "" : name;
            this.dimension = dimension == null ? "" : dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.createdAt = System.currentTimeMillis();
        }

        public String name() {
            return name;
        }

        public String dimension() {
            return dimension;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public String note() {
            return note;
        }

        public Waypoint note(String value) {
            this.note = value == null ? "" : value;
            return this;
        }

        public long createdAt() {
            return createdAt;
        }

        public void setCreatedAt(long value) {
            this.createdAt = value;
        }

        public void setName(String value) {
            this.name = value == null ? "" : value;
        }

        public void setDimension(String value) {
            this.dimension = value == null ? "" : value;
        }

        public void setPosition(int nx, int ny, int nz) {
            this.x = nx;
            this.y = ny;
            this.z = nz;
        }

        public String describe() {
            return name + " (" + x + ", " + y + ", " + z + ")" + (dimension.isEmpty() ? "" : " in " + dimension);
        }
    }

    /** A place the bot knows about, e.g. a chest, a farm or a resource patch. */
    public static final class KnownPlace {
        private String kind;
        private String label;
        private String dimension;
        private int x;
        private int y;
        private int z;
        private long seenAt;

        public KnownPlace(String kind, String label, String dimension, int x, int y, int z) {
            this.kind = kind == null ? "" : kind;
            this.label = label == null ? "" : label;
            this.dimension = dimension == null ? "" : dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.seenAt = System.currentTimeMillis();
        }

        public String kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        public String dimension() {
            return dimension;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public long seenAt() {
            return seenAt;
        }

        public void setSeenAt(long value) {
            this.seenAt = value;
        }
    }

    /** A completed or failed objective, kept for reporting. */
    public record ObjectiveRecord(String description, boolean success, String reason, long at) {
    }

    private int version = CURRENT_VERSION;
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();
    private final List<KnownPlace> places = new ArrayList<>();
    private final List<ObjectiveRecord> objectives = new ArrayList<>();
    private String homeName = "";
    private long updatedAt;

    public int version() {
        return version;
    }

    public void setVersion(int value) {
        this.version = value;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ waypoints

    public void putWaypoint(Waypoint waypoint) {
        if (waypoint == null || waypoint.name().isBlank()) return;
        waypoints.put(key(waypoint.name()), waypoint);
        touch();
    }

    public Optional<Waypoint> waypoint(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(waypoints.get(key(name)));
    }

    public boolean removeWaypoint(String name) {
        boolean removed = waypoints.remove(key(name)) != null;
        if (removed) touch();
        return removed;
    }

    public List<Waypoint> waypoints() {
        return new ArrayList<>(waypoints.values());
    }

    public void setHome(String name) {
        this.homeName = name == null ? "" : name;
        touch();
    }

    public String homeName() {
        return homeName;
    }

    public Optional<Waypoint> home() {
        return waypoint(homeName);
    }

    // ------------------------------------------------------------------ places

    public void addPlace(KnownPlace place) {
        if (place == null) return;
        // Replace a place of the same kind at the same coordinates so the list does not grow forever.
        places.removeIf(p -> p.kind().equals(place.kind()) && p.x() == place.x()
            && p.y() == place.y() && p.z() == place.z() && p.dimension().equals(place.dimension()));
        places.add(place);
        touch();
    }

    public List<KnownPlace> places(String kind) {
        List<KnownPlace> out = new ArrayList<>();
        for (KnownPlace place : places) {
            if (kind == null || place.kind().equals(kind)) out.add(place);
        }
        return out;
    }

    public void clearPlaces(String kind) {
        places.removeIf(place -> kind == null || place.kind().equals(kind));
        touch();
    }

    // ------------------------------------------------------------------ objectives

    public void recordObjective(String description, boolean success, String reason) {
        objectives.add(new ObjectiveRecord(description == null ? "" : description, success,
            reason == null ? "" : reason, System.currentTimeMillis()));
        while (objectives.size() > 200) objectives.remove(0);
        touch();
    }

    public List<ObjectiveRecord> objectives() {
        return new ArrayList<>(objectives);
    }

    // ------------------------------------------------------------------ maintenance

    /** Wipes all stored knowledge. */
    public void clear() {
        waypoints.clear();
        places.clear();
        objectives.clear();
        homeName = "";
        touch();
    }

    /** True when nothing has been stored yet. */
    public boolean isEmpty() {
        return waypoints.isEmpty() && places.isEmpty() && objectives.isEmpty() && homeName.isEmpty();
    }

    /**
     * Upgrades a raw stored map to {@link #CURRENT_VERSION}. Unknown or older versions are migrated
     * where a migration exists; anything newer than this build is rejected so a newer file is not
     * silently corrupted.
     *
     * @return {@code true} when the map is safe to load
     */
    public boolean migrate(Map<String, Object> raw) {
        if (raw == null) return false;
        Object rawVersion = raw.get("version");
        int stored = rawVersion instanceof Number number ? number.intValue() : 0;
        if (stored > CURRENT_VERSION) return false;
        // Version 0 (no version key) is the implicit shape of the first release; nothing to move.
        this.version = CURRENT_VERSION;
        return true;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
