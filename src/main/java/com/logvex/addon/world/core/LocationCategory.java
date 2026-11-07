package com.logvex.addon.world.core;

import java.util.Locale;

/** The kind of place a {@link WorldPoint} records, used to colour and group the map and list. */
public enum LocationCategory {
    BASE,
    STRUCTURE,
    RESOURCE,
    VILLAGE,
    PORTAL,
    WAYPOINT,
    DANGER,
    OTHER;

    /** Parses a stored name, falling back to {@link #OTHER} rather than throwing on bad data. */
    public static LocationCategory fromName(String name) {
        if (name == null) return OTHER;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    @Override
    public String toString() {
        String lower = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
