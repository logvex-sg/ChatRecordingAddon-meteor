package com.logvex.addon.chatlog.core;

import java.util.Locale;

/**
 * The accent a webhook embed uses.
 *
 * <p>{@link #AUTO} lets each card pick a colour from its message type (a join is green, a death is
 * red), while any other value forces one colour across every card. Exposed as an enum so the GUI can
 * offer a simple cycle button instead of a free-text colour field.
 */
public enum EmbedAccent {
    AUTO,
    BLURPLE,
    SLATE,
    GREEN,
    YELLOW,
    RED,
    PINK,
    TEAL,
    PURPLE,
    ORANGE;

    /** The lowercase name used by {@link EmbedColor#named(String)}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The style value that means "let each type choose". */
    public boolean isAuto() {
        return this == AUTO;
    }
}
