package com.logvex.addon.schematicdropper.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A block state: a registry id plus optional properties, e.g.
 * {@code minecraft:oak_stairs[facing=east,half=bottom]}.
 *
 * <p>Properties are stored in a sorted map so {@link #serialize()} is stable and two specs that
 * describe the same state always compare equal. This is the unit the comparison, dependency and
 * checkpoint layers operate on; none of them need Minecraft types.
 */
public final class BlockStateSpec {
    private static final BlockStateSpec AIR = new BlockStateSpec("minecraft:air", Map.of());

    private final String id;
    private final Map<String, String> properties;

    private BlockStateSpec(String id, Map<String, String> properties) {
        this.id = id;
        this.properties = Collections.unmodifiableMap(new TreeMap<>(properties));
    }

    public static BlockStateSpec of(String id, Map<String, String> properties) {
        return new BlockStateSpec(normalise(id), properties == null ? Map.of() : properties);
    }

    public static BlockStateSpec of(String id) {
        return of(id, Map.of());
    }

    public static BlockStateSpec air() {
        return AIR;
    }

    /** Parses {@code minecraft:oak_stairs[facing=east,half=bottom]} or a bare block id. */
    public static BlockStateSpec parse(String text) {
        if (text == null || text.isBlank()) return AIR;
        String trimmed = text.trim();

        int open = trimmed.indexOf('[');
        if (open < 0 || !trimmed.endsWith("]")) {
            return of(trimmed, Map.of());
        }

        String id = trimmed.substring(0, open).trim();
        String body = trimmed.substring(open + 1, trimmed.length() - 1);
        Map<String, String> props = new TreeMap<>();
        for (String pair : body.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            props.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        return of(id, props);
    }

    public String id() {
        return id;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    public boolean hasProperties() {
        return !properties.isEmpty();
    }

    /** The same block, ignoring properties. */
    public boolean sameBlock(BlockStateSpec other) {
        return other != null && id.equals(other.id);
    }

    /** The same block and every property this spec declares is also declared with the same value. */
    public boolean matches(BlockStateSpec other) {
        if (other == null || !id.equals(other.id)) return false;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!e.getValue().equals(other.properties.get(e.getKey()))) return false;
        }
        return true;
    }

    public String serialize() {
        if (properties.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String normalise(String id) {
        if (id == null || id.isBlank()) return "minecraft:air";
        String lower = id.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockStateSpec other)) return false;
        return id.equals(other.id) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, properties);
    }

    @Override
    public String toString() {
        return serialize();
    }
}
