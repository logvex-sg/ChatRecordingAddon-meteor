package com.logvex.addon.framework.gui.animation;

/**
 * The frame clock the GUI and HUD animate against.
 *
 * <p>Minecraft's render callback receives a <em>tick</em> delta that sawtooths between 0 and 1 twenty
 * times a second, so it is useless as a time base for a smooth animation. This clock is advanced once
 * per frame by the two render roots ({@code WidgetScreen} and {@code HudRenderer}) and hands every
 * widget the same, real elapsed time in seconds.
 *
 * <p>The delta is clamped so a stall (a world load, a debugger pause) cannot make an animation jump
 * to its end, and a minimum is applied so a zero-length frame never freezes an animator.
 */
public final class AnimationClock {
    private static final double MIN_DELTA = 1.0 / 240.0;
    private static final double MAX_DELTA = 0.1;

    private static double delta = 1.0 / 60.0;
    private static long lastNanos;
    private static long elapsedNanos;

    private AnimationClock() {
    }

    /** Advances the clock. Called once per frame by a render root. */
    public static void tick() {
        long now = System.nanoTime();
        if (lastNanos == 0) {
            lastNanos = now;
            return;
        }
        long diff = now - lastNanos;
        // The GUI and the in-game HUD can both drive the clock in the same frame; ignore a second
        // call that arrives within a millisecond so the delta is not halved.
        if (diff < 1_000_000L) return;
        lastNanos = now;
        elapsedNanos += diff;
        double seconds = diff / 1_000_000_000.0;
        delta = Math.max(MIN_DELTA, Math.min(MAX_DELTA, seconds));
    }

    /** Seconds since the previous frame, clamped to a usable range. */
    public static double delta() {
        return delta;
    }

    /** Seconds since the clock was first ticked, for looping or pulsing effects. */
    public static double time() {
        return elapsedNanos / 1_000_000_000.0;
    }

    /** Resets the clock; used by tests so they start from a known state. */
    public static void reset() {
        lastNanos = 0;
        elapsedNanos = 0;
        delta = 1.0 / 60.0;
    }
}
