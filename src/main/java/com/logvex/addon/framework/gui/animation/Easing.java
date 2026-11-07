package com.logvex.addon.framework.gui.animation;

/**
 * Easing functions for GUI animation.
 *
 * <p>Every function maps {@code t} in {@code [0, 1]} to an eased value. They are pure, static and
 * allocation-free so a widget can call one per frame without cost, and the shapes are the standard
 * ones a client interface expects: things speed up as they appear and settle gently at the end.
 */
public final class Easing {
    private Easing() {
    }

    /** No easing: the identity curve. */
    public static double linear(double t) {
        return clamp(t);
    }

    /** Starts slow and accelerates. */
    public static double quadIn(double t) {
        double x = clamp(t);
        return x * x;
    }

    /** Starts fast and decelerates. */
    public static double quadOut(double t) {
        double x = clamp(t);
        return 1 - (1 - x) * (1 - x);
    }

    /** Slow at both ends, fast in the middle. */
    public static double quadInOut(double t) {
        double x = clamp(t);
        return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
    }

    /** A stronger decelerating curve, for content that should settle quickly. */
    public static double cubicOut(double t) {
        double x = clamp(t);
        return 1 - Math.pow(1 - x, 3);
    }

    /** A gentler decelerating curve, for hovers and fades. */
    public static double sineOut(double t) {
        return Math.sin(clamp(t) * Math.PI / 2);
    }

    /** A slight overshoot past the target before settling, for pop-in effects. */
    public static double backOut(double t) {
        double x = clamp(t);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    /** Oscillates around the target as it settles. */
    public static double elasticOut(double t) {
        double x = clamp(t);
        if (x == 0 || x == 1) return x;
        double c4 = 2 * Math.PI / 3;
        return Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * c4) + 1;
    }

    private static double clamp(double t) {
        if (t < 0) return 0;
        if (t > 1) return 1;
        return t;
    }
}
