package com.logvex.addon.chatlog.core;

import java.util.Locale;

/**
 * A small palette of embed accent colours and the conversion helpers the embed builder needs.
 *
 * <p>Discord wants the accent as a decimal 24-bit integer, while the rest of the addon thinks in
 * hex. Keeping the mapping in one place means every embed uses the same palette and a colour can be
 * named in a setting instead of a raw number. Minecraft-free so it can be unit-tested directly.
 */
public final class EmbedColor {
    /** Discord's default blurple. */
    public static final int BLURPLE = 0x5865F2;
    /** Neutral slate for informational cards. */
    public static final int SLATE = 0x4F545C;
    /** Green: success, joins, completed work. */
    public static final int GREEN = 0x57F287;
    /** Yellow: a keyword hit or a warning. */
    public static final int YELLOW = 0xFEE75C;
    /** Red: a failure or a death. */
    public static final int RED = 0xED4245;
    /** Pink: a rename or an identity event. */
    public static final int PINK = 0xEB459E;
    /** Teal: chat activity. */
    public static final int TEAL = 0x1ABC9C;
    /** Purple: announcements and events. */
    public static final int PURPLE = 0x9B59B6;
    /** Orange: leaves and departures. */
    public static final int ORANGE = 0xE67E22;

    private EmbedColor() {
    }

    /** Formats an RGB value as {@code #RRGGBB}, dropping any alpha. */
    public static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Parses {@code #RRGGBB} or a bare {@code RRGGBB}. A value that does not look like a colour
     * returns {@code fallback}, so a typo in a setting degrades to a sane default rather than black.
     */
    public static int parse(String hex, int fallback) {
        if (hex == null) return fallback;
        String text = hex.trim();
        if (text.startsWith("#")) text = text.substring(1);
        if (text.length() != 6) return fallback;
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The accent that best fits a message type, so a card's colour carries meaning. */
    public static int forType(MessageType type) {
        if (type == null) return SLATE;
        return switch (type) {
            case PUBLIC_CHAT, PLAYER_MESSAGE -> TEAL;
            case JOIN -> GREEN;
            case LEAVE -> ORANGE;
            case DEATH -> RED;
            case ANNOUNCEMENT -> PURPLE;
            case SYSTEM -> SLATE;
            case GAME -> BLURPLE;
            case OTHER -> SLATE;
        };
    }

    /** A named palette colour, falling back to blurple when the name is unknown. */
    public static int named(String name) {
        if (name == null) return BLURPLE;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "blurple" -> BLURPLE;
            case "slate", "grey", "gray" -> SLATE;
            case "green" -> GREEN;
            case "yellow", "amber" -> YELLOW;
            case "red", "crimson" -> RED;
            case "pink", "magenta" -> PINK;
            case "teal", "cyan" -> TEAL;
            case "purple", "violet" -> PURPLE;
            case "orange" -> ORANGE;
            default -> BLURPLE;
        };
    }

    /** The names offered by the palette setting, in display order. */
    public static String[] names() {
        return new String[]{"blurple", "slate", "green", "yellow", "red", "pink", "teal", "purple", "orange"};
    }
}
