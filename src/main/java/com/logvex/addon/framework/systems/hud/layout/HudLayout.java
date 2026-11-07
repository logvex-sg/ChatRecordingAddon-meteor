package com.logvex.addon.framework.systems.hud.layout;

import java.util.List;

/**
 * The HUD's layout maths, with no Minecraft dependency so it can be tested directly.
 *
 * <p>An element is stored as an {@link HudAnchor} plus a pixel offset. Turning that into an absolute
 * rectangle needs the screen size and the element's own size, so the conversion lives here rather than
 * in the element: a centred element stays centred after a resize, and a right-anchored element keeps
 * its distance from the right edge. {@link #snap} and {@link #findFreeSpot} back the editor's grid
 * snapping and its "move out of the way" behaviour.
 */
public final class HudLayout {
    /** Distance kept from the screen edge by an anchored element. */
    public static final int DEFAULT_MARGIN = 4;
    /** Grid step the editor snaps to; 1 means no visible snapping. */
    public static final int DEFAULT_GRID = 1;

    private HudLayout() {
    }

    /** Absolute position of an anchored element, given the screen size. */
    public static HudRect resolve(HudAnchor anchor, int offsetX, int offsetY, int width, int height,
                                  int screenWidth, int screenHeight, int margin) {
        if (anchor == null) anchor = HudAnchor.TOP_LEFT;
        int x = anchor.resolveX(screenWidth, width, margin) + offsetX;
        int y = anchor.resolveY(screenHeight, height, margin) + offsetY;
        return new HudRect(x, y, width, height);
    }

    /**
     * The offset that places an element's top-left corner at {@code absX/absY} for the given anchor.
     * This is the inverse of {@link #resolve} and is what the editor uses after a drag.
     */
    public static int[] offsetFor(HudAnchor anchor, int absX, int absY, int width, int height,
                                  int screenWidth, int screenHeight, int margin) {
        if (anchor == null) anchor = HudAnchor.TOP_LEFT;
        int baseX = anchor.resolveX(screenWidth, width, margin);
        int baseY = anchor.resolveY(screenHeight, height, margin);
        return new int[]{absX - baseX, absY - baseY};
    }

    /** Rounds {@code value} to the nearest multiple of {@code grid}; a grid below 2 is a no-op. */
    public static int snap(int value, int grid) {
        if (grid < 2) return value;
        return Math.round((float) value / grid) * grid;
    }

    /** Moves a rectangle back inside the screen, never making it negative. */
    public static HudRect clamp(HudRect rect, int screenWidth, int screenHeight) {
        int x = Math.max(0, Math.min(rect.x(), Math.max(0, screenWidth - rect.width())));
        int y = Math.max(0, Math.min(rect.y(), Math.max(0, screenHeight - rect.height())));
        return rect.movedTo(x, y);
    }

    /** True when {@code candidate} overlaps any rectangle in {@code placed}. */
    public static boolean overlaps(List<HudRect> placed, HudRect candidate) {
        if (placed == null || candidate == null) return false;
        for (HudRect rect : placed) {
            if (rect != null && rect.intersects(candidate)) return true;
        }
        return false;
    }

    /**
     * Finds a position near {@code desired} that does not overlap anything in {@code placed}.
     *
     * <p>Scans downward in {@code step} increments first, then rightward, which keeps a newly enabled
     * element near where it was asked to go instead of throwing it to a far corner. Returns the
     * desired rectangle when nothing overlaps, or when no free spot is found within the screen.
     */
    public static HudRect findFreeSpot(HudRect desired, List<HudRect> placed, int screenWidth, int screenHeight, int step) {
        if (desired == null) return null;
        if (step < 1) step = 1;
        if (!overlaps(placed, desired)) return desired;

        int maxY = Math.max(0, screenHeight - desired.height());
        int maxX = Math.max(0, screenWidth - desired.width());
        for (int y = desired.y(); y <= maxY; y += step) {
            HudRect candidate = desired.movedTo(desired.x(), y);
            if (!overlaps(placed, candidate)) return candidate;
        }
        for (int x = 0; x <= maxX; x += step) {
            for (int y = 0; y <= maxY; y += step) {
                HudRect candidate = desired.movedTo(x, y);
                if (!overlaps(placed, candidate)) return candidate;
            }
        }
        return desired;
    }
}
