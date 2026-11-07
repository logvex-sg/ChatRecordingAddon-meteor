package com.logvex.addon.framework.systems.hud.layout;

/**
 * Where an element sits relative to the screen edge it is anchored to.
 *
 * <p>An anchor stores a fractional position (0 = start edge, 1 = end edge) so the same element can
 * hug the top-left corner or stay centred on a resized window without any stored pixel value going
 * stale. The stored x/y is then an offset from that anchor rather than an absolute pixel, which is
 * what keeps a layout sane when the window changes size or the GUI scale changes.
 */
public enum HudAnchor {
    TOP_LEFT(0, 0),
    TOP_CENTER(0.5, 0),
    TOP_RIGHT(1, 0),
    MIDDLE_LEFT(0, 0.5),
    CENTER(0.5, 0.5),
    MIDDLE_RIGHT(1, 0.5),
    BOTTOM_LEFT(0, 1),
    BOTTOM_CENTER(0.5, 1),
    BOTTOM_RIGHT(1, 1);

    /** 0 anchors to the left/top edge, 0.5 centres, 1 anchors to the right/bottom edge. */
    public final double fx;
    public final double fy;

    HudAnchor(double fx, double fy) {
        this.fx = fx;
        this.fy = fy;
    }

    /** True when this anchor tracks the left edge, so a resize must not move the element. */
    public boolean isLeft() {
        return fx == 0;
    }

    public boolean isRight() {
        return fx == 1;
    }

    public boolean isTop() {
        return fy == 0;
    }

    public boolean isBottom() {
        return fy == 1;
    }

    /** Horizontal position of an element of {@code width} on a {@code screenWidth} screen. */
    public int resolveX(int screenWidth, int width, int margin) {
        return (int) Math.round(margin + fx * Math.max(0, screenWidth - width - 2 * margin));
    }

    /** Vertical position of an element of {@code height} on a {@code screenHeight} screen. */
    public int resolveY(int screenHeight, int height, int margin) {
        return (int) Math.round(margin + fy * Math.max(0, screenHeight - height - 2 * margin));
    }

    /** Parses a stored name case-insensitively, returning {@code fallback} when it does not match. */
    public static HudAnchor parse(String value, HudAnchor fallback) {
        if (value == null || value.isBlank()) return fallback;
        String needle = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (HudAnchor anchor : values()) {
            if (anchor.name().equals(needle)) return anchor;
        }
        return fallback;
    }
}
