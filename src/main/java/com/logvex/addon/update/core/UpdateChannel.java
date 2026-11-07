package com.logvex.addon.update.core;

import java.util.Locale;

/**
 * Which releases the update check is willing to offer.
 *
 * <p>{@link #STABLE} ignores anything marked as a pre-release; {@link #BETA} accepts both stable and
 * pre-release builds, preferring the newest of either. The choice is persisted and surfaced in the
 * GUI and the {@code /log update channel} command.
 */
public enum UpdateChannel {
    STABLE("Stable"),
    BETA("Beta");

    private final String label;

    UpdateChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True when a release with this pre-release flag should be considered on this channel. */
    public boolean accepts(boolean preRelease) {
        return this == BETA || !preRelease;
    }

    /** Parses a channel name case-insensitively, returning {@code fallback} when it does not match. */
    public static UpdateChannel parse(String value, UpdateChannel fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        for (UpdateChannel channel : values()) {
            if (channel.name().toLowerCase(Locale.ROOT).equals(trimmed)) return channel;
        }
        return fallback;
    }
}
