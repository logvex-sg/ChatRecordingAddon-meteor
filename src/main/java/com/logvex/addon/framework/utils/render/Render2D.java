package com.logvex.addon.framework.utils.render;

import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.gui.DrawContext;

/**
 * Low-level drawing primitives shared by the GUI and the HUD.
 *
 * <p>Minecraft's {@link DrawContext} only knows how to draw axis-aligned rectangles, so anything
 * that looks like a "modern" client interface has to be built from many of them. Keeping that
 * arithmetic here means the GUI widgets and the HUD panels round their corners, glow and fade with
 * exactly the same code, so the two surfaces cannot drift apart visually.
 *
 * <p>Every method takes packed ARGB ints and never allocates, because these run many times per frame.
 * Alpha is composed by {@link #withAlpha} rather than by mutating a shared {@link Color}, so a
 * caller's palette is never modified as a side effect of drawing.
 */
public final class Render2D {
    private Render2D() {
    }

    /** Packs a {@link Color} into ARGB, treating a zero alpha as fully opaque. */
    public static int argb(Color color) {
        if (color == null) return 0xFFFFFFFF;
        int a = color.a == 0 ? 255 : color.a;
        return (a << 24) | (color.r << 16) | (color.g << 8) | color.b;
    }

    /** Packs a {@link Color} with an explicit alpha, ignoring the colour's own alpha. */
    public static int argb(Color color, int alpha) {
        if (color == null) return 0xFFFFFFFF;
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (color.r << 16) | (color.g << 8) | color.b;
    }

    /** Packs components into ARGB. */
    public static int argb(int r, int g, int b, int a) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** Returns {@code argb} with its alpha multiplied by {@code factor} (0..1). */
    public static int withAlpha(int argb, double factor) {
        int a = (argb >>> 24) & 0xFF;
        int scaled = clamp((int) Math.round(a * Math.max(0, Math.min(1, factor))));
        return (scaled << 24) | (argb & 0x00FFFFFF);
    }

    /** Replaces the alpha channel of {@code argb}. */
    public static int alphaOf(int argb, int alpha) {
        return (clamp(alpha) << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Linear blend between two packed ARGB colours.
     *
     * @param t 0 returns {@code from}, 1 returns {@code to}; values outside are clamped
     */
    public static int blend(int from, int to, double t) {
        double f = Math.max(0, Math.min(1, t));
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, f);
        int r = lerpChannel((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, f);
        int g = lerpChannel((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, f);
        int b = lerpChannel(from & 0xFF, to & 0xFF, f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, double t) {
        return clamp((int) Math.round(from + (to - from) * t));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ---------------------------------------------------------------- rectangles

    /** A plain filled rectangle, with the coordinates rounded to whole pixels. */
    public static void rect(DrawContext context, double x, double y, double width, double height, int argb) {
        if (context == null || width <= 0 || height <= 0) return;
        context.fill(round(x), round(y), round(x + width), round(y + height), argb);
    }

    /**
     * A filled rectangle with rounded corners.
     *
     * <p>The radius is clamped to half the shorter side so a thin bar still draws. Only the corner
     * arcs cost extra fills, so this stays cheap enough for a list of rows.
     */
    public static void roundedRect(DrawContext context, double x, double y, double width, double height,
                                   double radius, int argb) {
        if (context == null || width <= 0 || height <= 0) return;
        int xi = round(x);
        int yi = round(y);
        int wi = Math.max(1, round(width));
        int hi = Math.max(1, round(height));
        int r = Math.max(0, Math.min(round(radius), Math.min(wi, hi) / 2));

        if (r == 0) {
            context.fill(xi, yi, xi + wi, yi + hi, argb);
            return;
        }
        // Body between the four corners, then the two straight side strips, then the arcs.
        context.fill(xi + r, yi, xi + wi - r, yi + hi, argb);
        context.fill(xi, yi + r, xi + r, yi + hi - r, argb);
        context.fill(xi + wi - r, yi + r, xi + wi, yi + hi - r, argb);
        drawCorners(context, xi, yi, wi, hi, r, argb);
    }

    /** A 1-pixel (or thicker) rounded outline, drawn just inside the given bounds. */
    public static void roundedOutline(DrawContext context, double x, double y, double width, double height,
                                      double radius, int argb, double thickness) {
        if (context == null || width <= 0 || height <= 0) return;
        int xi = round(x);
        int yi = round(y);
        int wi = Math.max(1, round(width));
        int hi = Math.max(1, round(height));
        int t = Math.max(1, round(thickness));
        int r = Math.max(0, Math.min(round(radius), Math.min(wi, hi) / 2));

        // Top and bottom edges, then the left and right edges, inset so the line stays inside.
        context.fill(xi + r, yi, xi + wi - r, yi + t, argb);
        context.fill(xi + r, yi + hi - t, xi + wi - r, yi + hi, argb);
        context.fill(xi, yi + r, xi + t, yi + hi - r, argb);
        context.fill(xi + wi - t, yi + r, xi + wi, yi + hi - r, argb);
        if (r > 0) drawCornerOutline(context, xi, yi, wi, hi, r, t, argb);
    }

    /**
     * A soft glow around a rounded rectangle.
     *
     * <p>This is what makes an active element read as "lit": {@code layers} translucent copies of the
     * shape are drawn progressively larger and dimmer, so the brightness falls off towards the edge.
     * The shape itself is not drawn here, so a caller can glow behind a button and then paint it.
     */
    public static void glow(DrawContext context, double x, double y, double width, double height,
                            double radius, int argb, int layers, double spread) {
        if (context == null || width <= 0 || height <= 0 || layers <= 0) return;
        for (int i = layers; i >= 1; i--) {
            double grow = spread * i / layers;
            double falloff = 1.0 - (double) i / (layers + 1);
            int layerAlpha = (int) Math.round(((argb >>> 24) & 0xFF) * 0.10 * falloff);
            if (layerAlpha <= 0) continue;
            roundedRect(context, x - grow, y - grow, width + grow * 2, height + grow * 2,
                radius + grow, alphaOf(argb, layerAlpha));
        }
    }

    /** A vertical gradient, used for headers and title bars. */
    public static void gradientVertical(DrawContext context, double x, double y, double width, double height,
                                        int top, int bottom) {
        if (context == null || width <= 0 || height <= 0) return;
        context.fillGradient(round(x), round(y), round(x + width), round(y + height), top, bottom);
    }

    /**
     * A horizontal gradient inside a rounded rectangle.
     *
     * <p>Drawn as one vertical strip per column so the corners stay rounded, which is what gives an
     * active tab or a filled progress bar its "lit" look.
     */
    public static void gradientHorizontalRounded(DrawContext context, double x, double y, double width,
                                                 double height, double radius, int left, int right) {
        if (context == null || width <= 0 || height <= 0) return;
        int wi = Math.max(1, round(width));
        for (int i = 0; i < wi; i++) {
            double t = wi <= 1 ? 0 : (double) i / (wi - 1);
            int column = blend(left, right, t);
            roundedRect(context, x + i, y, 1, height, radius, column);
        }
    }

    /**
     * A line segment of a given thickness.
     *
     * <p>Drawn as a run of short vertical or horizontal bars rather than as a rotated quad, which
     * avoids touching the matrix stack and keeps a check mark or a divider cheap. The line is
     * rasterised by stepping along its longer axis.
     */
    public static void line(DrawContext context, double x1, double y1, double x2, double y2,
                            double thickness, int argb) {
        if (context == null) return;
        double dx = x2 - x1;
        double dy = y2 - y1;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) {
            rect(context, x1, y1, Math.max(1, thickness), Math.max(1, thickness), argb);
            return;
        }
        double half = Math.max(0.5, thickness / 2);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + dx * t;
            double py = y1 + dy * t;
            rect(context, px - half, py - half, thickness, thickness, argb);
        }
    }

    private static void drawCorners(DrawContext context, int xi, int yi, int wi, int hi, int r, int argb) {
        for (int i = 0; i < r; i++) {
            int inset = arcInset(r, i);
            context.fill(xi + inset, yi + i, xi + r, yi + i + 1, argb);
            context.fill(xi + wi - r, yi + i, xi + wi - inset, yi + i + 1, argb);
            context.fill(xi + inset, yi + hi - i - 1, xi + r, yi + hi - i, argb);
            context.fill(xi + wi - r, yi + hi - i - 1, xi + wi - inset, yi + hi - i, argb);
        }
    }

    private static void drawCornerOutline(DrawContext context, int xi, int yi, int wi, int hi, int r, int t, int argb) {
        for (int i = 0; i < r; i++) {
            int inset = arcInset(r, i);
            context.fill(xi + inset, yi + i, xi + r, yi + i + t, argb);
            context.fill(xi + wi - r, yi + i, xi + wi - inset, yi + i + t, argb);
            context.fill(xi + inset, yi + hi - i - t, xi + r, yi + hi - i, argb);
            context.fill(xi + wi - r, yi + hi - i - t, xi + wi - inset, yi + hi - i, argb);
        }
    }

    /** How far a corner pixel at row {@code i} is inset, from a quarter-circle of radius {@code r}. */
    private static int arcInset(int r, int i) {
        double dy = r - i;
        double dx = Math.sqrt(Math.max(0, r * r - dy * dy));
        return r - (int) Math.round(dx);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }
}
