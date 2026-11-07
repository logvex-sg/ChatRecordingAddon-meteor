package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

/**
 * Shared panel layout for HUD elements.
 *
 * <p>Every element measures and draws through these helpers, so padding, line spacing, the title row
 * and the panel shape are identical across the whole HUD. An element therefore only decides
 * <em>what</em> to show: it builds a list of lines, hands them here and gets back a sized, drawn box.
 * That is what stops eleven elements drifting into eleven slightly different layouts.
 */
public final class HudPanel {
    /** Inner padding between the panel edge and its content. */
    public static final double PAD = 4;
    /** Extra gap between two text lines, on top of the font height. */
    public static final double LINE_GAP = 1;
    /** Corner radius of the panel and its border. */
    public static final int RADIUS = 3;

    private HudPanel() {
    }

    /** The text scale an element should draw with: its own when set, otherwise the global one (-1). */
    public static double scale(HudSettings.Shared shared) {
        return shared.customScale.get() ? shared.scale.get() : -1;
    }

    /** Resolves the element's palette, following the theme unless a colour was overridden. */
    public static HudStyle style(HudSettings.Shared shared, HudThemeColors theme) {
        return HudStyle.resolve(
            !shared.useThemeBackground.get(), shared.backgroundColor.get(),
            shared.border.get(), shared.borderColor.get(),
            !shared.useThemeTitle.get(), shared.titleColor.get(),
            !shared.useThemeText.get(), shared.textColor.get(),
            false, null,
            theme);
    }

    /** One text line's height, including the gap to the next line. */
    public static double lineHeight(HudRenderer renderer, double scale) {
        return renderer.textHeight(true, scale) + LINE_GAP;
    }

    /**
     * Sizes {@code element} for a titled panel holding {@code lines}. The width is the widest of the
     * title and the lines; the height is the title row plus every line, with padding.
     */
    public static void measure(HudElement element, HudRenderer renderer, double scale, boolean shadow,
                               String title, List<String> lines) {
        double lineHeight = renderer.textHeight(shadow, scale) + LINE_GAP;
        double width = title == null ? 0 : renderer.textWidth(title, shadow, scale);
        int rows = 0;
        if (lines != null) {
            for (String line : lines) {
                if (line == null) continue;
                width = Math.max(width, renderer.textWidth(line, shadow, scale));
                rows++;
            }
        }
        if (rows == 0) rows = 1;
        double height = lineHeight * (title == null || title.isEmpty() ? rows : rows + 1);
        element.setSize(width + PAD * 2, height + PAD * 2);
    }

    /**
     * Draws the panel background and border and the title row, returning the y of the first content
     * line. A {@code null} or empty title is skipped and no title row is reserved.
     */
    public static double frame(HudRenderer renderer, HudElement element, HudStyle style,
                               boolean background, boolean border, String title, double scale, boolean shadow) {
        return frame(renderer, element, style, background, border, title, null, scale, shadow);
    }

    /**
     * As {@link #frame(HudRenderer, HudElement, HudStyle, boolean, boolean, String, double, boolean)} but
     * with an optional title colour override (for a title that reflects state, e.g. an error).
     */
    public static double frame(HudRenderer renderer, HudElement element, HudStyle style,
                               boolean background, boolean border, String title, Color titleColor,
                               double scale, boolean shadow) {
        double x = element.getX();
        double y = element.getY();
        double width = element.getWidth();
        double height = element.getHeight();

        if (background) renderer.panel(x, y, width, height, style.background(), RADIUS);
        if (border) renderer.outline(x, y, width, height, style.border(), RADIUS);

        double cursor = y + PAD;
        if (title != null && !title.isEmpty()) {
            renderer.text(title, x + PAD, cursor, titleColor == null ? style.title() : titleColor, shadow, scale);
            cursor += lineHeight(renderer, scale);
        }
        return cursor;
    }

    /** Draws one content line and returns the y for the next. */
    public static double line(HudRenderer renderer, HudElement element, String text, double cursor,
                              Color color, double scale, boolean shadow) {
        renderer.text(text, element.getX() + PAD, cursor, color, shadow, scale);
        return cursor + lineHeight(renderer, scale);
    }

    /** Draws a right-aligned value on the same row as a label, for "label ....... value" rows. */
    public static void keyValue(HudRenderer renderer, HudElement element, String key, String value,
                                double cursor, Color keyColor, Color valueColor, double scale, boolean shadow) {
        renderer.text(key, element.getX() + PAD, cursor, keyColor, shadow, scale);
        renderer.textRight(value, element.getX() + element.getWidth() - PAD, cursor, valueColor, shadow, scale);
    }
}
