package com.logvex.addon.assistant.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who is allowed to fire a keyword trigger.
 *
 * <p>This is separate from {@link PermissionSet}, which decides what a player may <em>command</em>.
 * A command is an explicit request, so an open default is reasonable. A trigger is the assistant
 * speaking unprompted: on a busy server, ordinary chatter ("gg", "lol", "ty") matches the shipped
 * table constantly, and a default that answers every stranger turns the assistant into noise that
 * nobody asked for. The gate exists so "the assistant talks to everyone" is a choice.
 *
 * <p>The local player is always allowed. Silencing the person who installed the mod would be a bug,
 * not a safety feature, and their identity is the one the client can prove.
 */
public enum TriggerAudience {
    /** Anyone who speaks may trigger a reply. The historical behaviour. */
    EVERYONE,

    /**
     * Only the local player and the listed names may trigger a reply.
     *
     * <p>An empty list therefore means "only me", which is what a fresh install gets: the assistant
     * stays quiet around strangers until the user names who it should answer.
     */
    ALLOWLIST,

    /** Only the local player may trigger a reply; the list is ignored. */
    LOCAL_ONLY;

    /**
     * True when {@code sender} may fire a trigger under this audience.
     *
     * <p>Name matching is case-insensitive because a chat line's capitalisation is not meaningful
     * and Minecraft names are unique case-insensitively.
     */
    public boolean allows(SenderIdentity sender, Set<String> names) {
        if (sender == null) return false;
        if (sender.local()) return true;
        return switch (this) {
            case EVERYONE -> true;
            case LOCAL_ONLY -> false;
            case ALLOWLIST -> names != null && names.contains(lower(sender.name()));
        };
    }

    /** Convenience overload that builds the set from a config list. */
    public boolean allows(SenderIdentity sender, List<String> names) {
        return allows(sender, normalise(names));
    }

    /** The names as a lower-cased set, dropping blanks. */
    public static Set<String> normalise(List<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) out.add(lower(trimmed));
        }
        return Set.copyOf(out);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** A one-line description for the settings GUI and the status command. */
    public String describe(int allowListSize) {
        return switch (this) {
            case EVERYONE -> "anyone in chat";
            case LOCAL_ONLY -> "only you";
            case ALLOWLIST -> allowListSize == 0
                ? "only you (no names listed)"
                : "you and " + allowListSize + (allowListSize == 1 ? " name" : " names");
        };
    }
}
