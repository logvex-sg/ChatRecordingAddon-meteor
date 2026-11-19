package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The permission rule for one assistant command.
 *
 * <p>A rule is a level plus, for {@link PermissionLevel#WHITELIST}, the players allowed to run that
 * command. Entries are stored as either a bare name ({@code PlayerOne}) or a name pinned to an
 * account ({@code PlayerOne=069a79f4-44e9-4726-a5be-fca90e38aaf5}). A pinned entry matches the
 * speaker's resolved UUID only, so a spoofed name cannot impersonate it.
 *
 * <p>Encoding is {@code command;whitelist=true;users=A,B;level=whitelist} — the same
 * {@code key=value} shape the trigger rules already use, so it round-trips through Meteor's
 * {@code StringListSetting} with no bespoke serialiser and survives a hand edit.
 */
public record CommandPermission(
    String command,
    PermissionLevel level,
    List<String> users,
    boolean enabled
) {
    /** The level a command falls back to when its rule is missing or unreadable. */
    public static final PermissionLevel DEFAULT_LEVEL = PermissionLevel.EVERYONE;

    public CommandPermission {
        if (command == null) command = "";
        command = command.trim().toLowerCase(Locale.ROOT);
        if (level == null) level = DEFAULT_LEVEL;
        users = sanitize(users);
    }

    /** A rule with the default level and no listed users. */
    public static CommandPermission open(String command) {
        return new CommandPermission(command, DEFAULT_LEVEL, List.of(), true);
    }

    /** A rule restricted to {@code users}. */
    public static CommandPermission whitelisted(String command, List<String> users) {
        return new CommandPermission(command, PermissionLevel.WHITELIST, users, true);
    }

    /**
     * Drops blank entries, de-duplicates case-insensitively and trims, keeping the first spelling.
     *
     * <p>Validation is deliberately gentle: a malformed entry is dropped rather than making the
     * whole rule unreadable, so one typo cannot silently open a command to everyone.
     */
    private static List<String> sanitize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String entry : raw) {
            String cleaned = cleanEntry(entry);
            if (cleaned == null) continue;
            if (seen.add(cleaned.toLowerCase(Locale.ROOT))) out.add(cleaned);
        }
        return List.copyOf(out);
    }

    /** Normalises one entry, or null when it is not a legal name / name=uuid pair. */
    public static String cleanEntry(String entry) {
        if (entry == null) return null;
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) return null;

        int eq = trimmed.indexOf('=');
        if (eq < 0) {
            return SenderIdentity.validName(trimmed) ? trimmed : null;
        }
        String name = trimmed.substring(0, eq).trim();
        String uuidText = trimmed.substring(eq + 1).trim();
        if (!SenderIdentity.validName(name)) return null;
        if (SenderIdentity.parseUuid(uuidText) == null) return null;
        return name + "=" + uuidText.toLowerCase(Locale.ROOT).replace("-", "");
    }

    /** True when {@code entry} names {@code name}, ignoring case. */
    public static boolean entryMatchesName(String entry, String name) {
        if (entry == null || name == null) return false;
        int eq = entry.indexOf('=');
        String entryName = eq < 0 ? entry : entry.substring(0, eq);
        return entryName.trim().equalsIgnoreCase(name.trim());
    }

    /** The UUID a pinned entry requires, or null for a name-only entry. */
    public static java.util.UUID entryUuid(String entry) {
        if (entry == null) return null;
        int eq = entry.indexOf('=');
        if (eq < 0) return null;
        return SenderIdentity.parseUuid(entry.substring(eq + 1));
    }

    /** True when this rule names {@code command}. */
    public boolean covers(String candidate) {
        return candidate != null && command.equals(candidate.trim().toLowerCase(Locale.ROOT));
    }

    /** A copy with a different level. */
    public CommandPermission withLevel(PermissionLevel newLevel) {
        return new CommandPermission(command, newLevel, users, enabled);
    }

    /** A copy with different users. */
    public CommandPermission withUsers(List<String> newUsers) {
        return new CommandPermission(command, level, newUsers, enabled);
    }

    /** A copy with the enabled flag flipped or set. */
    public CommandPermission withEnabled(boolean value) {
        return new CommandPermission(command, level, users, value);
    }

    public String encode() {
        return command + ";level=" + level.id()
            + ";whitelist=" + (level == PermissionLevel.WHITELIST)
            + ";users=" + String.join(",", users)
            + ";enabled=" + enabled;
    }

    /** Parses an encoded rule, returning null when it has no command name. */
    public static CommandPermission decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split(";", -1);
        String command = parts[0].trim().toLowerCase(Locale.ROOT);
        if (command.isEmpty()) return null;

        PermissionLevel level = null;
        boolean levelGiven = false;
        boolean legacyWhitelist = false;
        List<String> users = new ArrayList<>();
        boolean enabled = true;

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String key = parts[i].substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(eq + 1).trim();
            switch (key) {
                case "level" -> {
                    levelGiven = true;
                    level = PermissionLevel.parse(value, null);
                }
                case "whitelist" -> legacyWhitelist = Boolean.parseBoolean(value);
                case "users", "allowed" -> {
                    for (String entry : value.split(",")) users.add(entry);
                }
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }
        // A rule written by an older build carries only whitelist=true/false, so honour that when
        // there is no level key at all. This is what makes an old configuration migrate cleanly.
        if (level == null) {
            level = levelGiven ? PermissionLevel.LOCAL_ONLY
                : legacyWhitelist ? PermissionLevel.WHITELIST : DEFAULT_LEVEL;
        }
        return new CommandPermission(command, level, users, enabled);
    }

    /** A map form, for JSON and for the GUI. */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("command", command);
        map.put("level", level.id());
        map.put("users", List.copyOf(users));
        map.put("enabled", enabled);
        return map;
    }
}
