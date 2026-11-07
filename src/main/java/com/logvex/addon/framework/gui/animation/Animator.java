package com.logvex.addon.framework.gui.animation;

/**
 * A single animated value that eases towards a target over time.
 *
 * <p>Each instance remembers where it is and how long it has been travelling. A widget calls
 * {@link #update(double)} once per frame with the frame delta in seconds, then reads {@link #value()}.
 * When the value reaches the target the animator reports {@link #settled()} so a caller can skip
 * redundant work.
 *
 * <p>Animators are intentionally tiny and allocation-free; a screen keeps one per animated property
 * (a hover, a selection bar) rather than creating one per frame. {@link #snap(double)} jumps straight
 * to a value, which is what a freshly built screen uses so nothing animates in from zero on open.
 */
public final class Animator {
    private final double durationSeconds;
    private final EasingFunction easing;

    private double from;
    private double to;
    private double value;
    private double elapsed;
    private boolean settled = true;

    /** A function that eases a 0..1 progress value. */
    @FunctionalInterface
    public interface EasingFunction {
        double apply(double t);
    }

    public Animator(double initial, double durationSeconds, EasingFunction easing) {
        this.value = initial;
        this.from = initial;
        this.to = initial;
        this.durationSeconds = Math.max(0, durationSeconds);
        this.easing = easing == null ? Easing::linear : easing;
    }

    /** An animator with the common "settle quickly" curve. */
    public static Animator of(double initial) {
        return new Animator(initial, 0.18, Easing::cubicOut);
    }

    /** Retargets the animator, keeping the current value as the new starting point. */
    public void target(double next) {
        if (next == to) return;
        this.from = value;
        this.to = next;
        this.elapsed = 0;
        this.settled = false;
    }

    /** Jumps straight to {@code next} without animating. */
    public void snap(double next) {
        this.value = next;
        this.from = next;
        this.to = next;
        this.elapsed = 0;
        this.settled = true;
    }

    /**
     * Advances the animation.
     *
     * @param deltaSeconds seconds since the previous frame
     */
    public void update(double deltaSeconds) {
        if (settled) return;
        if (durationSeconds <= 0) {
            value = to;
            settled = true;
            return;
        }
        elapsed += Math.max(0, deltaSeconds);
        double t = elapsed / durationSeconds;
        if (t >= 1) {
            value = to;
            settled = true;
            return;
        }
        value = from + (to - from) * easing.apply(t);
    }

    public double value() {
        return value;
    }

    public double targetValue() {
        return to;
    }

    public boolean settled() {
        return settled;
    }
}
