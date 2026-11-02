package com.logvex.addon.schematicdropper.core;

/**
 * Pure rotation mathematics. No Minecraft types, so it is directly unit-testable.
 *
 * <p>Conventions match Minecraft: yaw is degrees clockwise from south (-Z), pitch is degrees
 * down from the horizon.
 */
public final class RotationMath {
    private RotationMath() {
    }

    /**
     * Yaw in degrees from {@code (sx,sz)} to {@code (tx,tz)}.
     * Result is normalised to (-180, 180].
     */
    public static float yawTo(double sx, double sz, double tx, double tz) {
        double dx = tx - sx;
        double dz = tz - sz;
        if (dx == 0.0 && dz == 0.0) return 0f;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return wrap(yaw);
    }

    /**
     * Pitch in degrees from an eye at height {@code sy} toward a target at height {@code ty}
     * with horizontal distance {@code horizontalDistance}. Positive pitch looks down.
     * Clamped to [-90, 90].
     */
    public static float pitchTo(double sy, double ty, double horizontalDistance) {
        double dy = ty - sy;
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        return (float) clamp(pitch, -90.0, 90.0);
    }

    /** Full rotation from source eye position to a target point. */
    public static Rotation rotationTo(double sx, double sy, double sz, double tx, double ty, double tz) {
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;

        if (!isFinite(dx) || !isFinite(dy) || !isFinite(dz)) return new Rotation(0f, 0f);

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = yawTo(sx, sz, tx, tz);
        float pitch = pitchTo(sy, ty, horizontal);
        return new Rotation(yaw, pitch);
    }

    /** Normalises an angle to (-180, 180]. */
    public static float wrap(double angle) {
        double a = angle % 360.0;
        if (a > 180.0) a -= 360.0;
        if (a <= -180.0) a += 360.0;
        return (float) a;
    }

    /** Shortest signed difference from {@code from} to {@code to}, in (-180, 180]. */
    public static float delta(float from, float to) {
        return wrap(to - from);
    }

    /**
     * Steps {@code current} toward {@code target} by at most {@code maxStep} degrees,
     * taking the shortest way around the circle.
     *
     * @return the new angle, wrapped to (-180, 180]
     */
    public static float stepToward(float current, float target, float maxStep) {
        if (maxStep <= 0f) return wrap(current);
        float d = delta(current, target);
        if (Math.abs(d) <= maxStep) return wrap(target);
        return wrap(current + Math.signum(d) * maxStep);
    }

    /** True when two angles are within {@code tolerance} degrees of each other. */
    public static boolean within(float a, float b, float tolerance) {
        return Math.abs(delta(a, b)) <= tolerance;
    }

    /** Wraps an angle into [0, 360). */
    public static float wrap360(double angle) {
        double a = angle % 360.0;
        if (a < 0) a += 360.0;
        return (float) a;
    }

    /**
     * Linear interpolation between two yaw angles, taking the shortest way around the circle.
     *
     * @param t interpolation factor, clamped to [0, 1]
     * @return a yaw in [0, 360)
     */
    public static float lerpDegrees(float from, float to, float t) {
        float clamped = t < 0f ? 0f : Math.min(t, 1f);
        return wrap360(from + delta(from, to) * clamped);
    }

    public static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    public static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
