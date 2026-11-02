package com.logvex.addon.schematicdropper.core;

/** A yaw/pitch pair in degrees. */
public record Rotation(float yaw, float pitch) {
    public static Rotation of(double yaw, double pitch) {
        return new Rotation((float) yaw, (float) pitch);
    }
}
