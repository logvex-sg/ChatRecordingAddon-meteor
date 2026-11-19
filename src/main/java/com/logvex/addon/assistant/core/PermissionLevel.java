package com.logvex.addon.assistant.core;

import java.util.Locale;

/**
 * Who may run one assistant command.
 *
 * <p>Four levels, ordered from most open to most closed. The ordering matters: a command's
 * <em>default</em> level is the fallback when nothing is configured, and it is deliberately
 * conservative, so a missing or corrupt configuration can only ever make a command quieter — never
 * louder. That is why the levels are declared in this order and why {@link #stricterThan} exists.
 *
 * <p>Every level is checked before the command runs, not after. See
 * {@link PermissionSet#permits}.
 */
public enum PermissionLevel {
    /** Any player in chat may trigger the command. */
    EVERYONE("everyone", "Anyone in chat"),

    /** Only players on the command's own whitelist may trigger it. */
    WHITELIST("whitelist", "Whitelisted players only"),

    /** Only players on the administrator list may trigger it. */
    ADMINISTRATORS("administrators", "Administrators only"),

    /** Only the local player may trigger it; chat from anyone else is refused. */
    LOCAL_ONLY("local-only", "Local player only"),

    /**
     * Switched off entirely: nobody may run it, and help does not list it.
     *
     * <p>This is a level rather than a separate flag because it must flow through the same single
     * decision point. A rule a user disabled has to be refused for the local player too — otherwise
     * "disabled" would only mean "hidden from other people", which is not what it says.
     */
    DISABLED("disabled", "Switched off");

    private final String id;
    private final String description;

    PermissionLevel(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** The stable identifier used in configuration. */
    public String id() {
        return id;
    }

    /** A short human-readable explanation, for the GUI and help text. */
    public String description() {
        return description;
    }

    /** Parses a stored value, falling back when it is absent or unrecognised. */
    public static PermissionLevel parse(String value, PermissionLevel fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalised = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalised) {
            case "everyone", "all", "any", "public" -> EVERYONE;
            case "whitelist", "whitelisted", "listed", "users" -> WHITELIST;
            case "administrators", "admins", "admin", "operators", "ops" -> ADMINISTRATORS;
            case "local-only", "local", "self", "me" -> LOCAL_ONLY;
            case "disabled", "off", "none", "never" -> DISABLED;
            default -> fallback;
        };
    }

    /** True when this level restricts more than {@code other}. */
    public boolean stricterThan(PermissionLevel other) {
        return other != null && ordinal() > other.ordinal();
    }

    /** The stricter of two levels; null-safe, preferring the non-null one. */
    public static PermissionLevel stricter(PermissionLevel a, PermissionLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
