package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bearing arithmetic behind the radar.
 *
 * <p>A wrong bearing is invisible: the dot still lands inside the circle, so nothing looks broken.
 * These cases pin the four cardinal directions and the yaw conversion, which is the part that is
 * easy to get backwards, because a Minecraft yaw counts clockwise from south rather than north.
 */
class CompanionGeometryTest {

    @Test
    @DisplayName("bearing reads north at the top and east on the right")
    void cardinalBearings() {
        assertEquals(0.0, CompanionGeometry.bearing(0, -10), 0.0001, "due north");
        assertEquals(90.0, CompanionGeometry.bearing(10, 0), 0.0001, "due east");
        assertEquals(180.0, CompanionGeometry.bearing(0, 10), 0.0001, "due south");
        assertEquals(-90.0, CompanionGeometry.bearing(-10, 0), 0.0001, "due west");
    }

    @Test
    @DisplayName("an entity at the player's feet has no bearing rather than NaN")
    void coincidentPoints() {
        assertEquals(0.0, CompanionGeometry.bearing(0, 0), 0.0001);
    }

    @Test
    @DisplayName("a Minecraft yaw converts to the bearing the app shows")
    void yawToBearing() {
        // Yaw 0 faces south, so the bearing is 180; yaw 90 faces west, i.e. bearing 270.
        assertEquals(180.0, CompanionGeometry.yawToBearing(0f), 0.0001);
        assertEquals(270.0, CompanionGeometry.yawToBearing(90f), 0.0001);
        assertEquals(0.0, CompanionGeometry.yawToBearing(180f), 0.0001);
        assertEquals(90.0, CompanionGeometry.yawToBearing(-90f), 0.0001);
        // A full turn comes back to where it started instead of drifting to 540.
        assertEquals(180.0, CompanionGeometry.yawToBearing(360f), 0.0001);
    }

    @Test
    @DisplayName("compass names land on the eight points and wrap around")
    void names() {
        assertEquals("north", CompanionGeometry.bearingName(0));
        assertEquals("north-east", CompanionGeometry.bearingName(44));
        assertEquals("east", CompanionGeometry.bearingName(90));
        assertEquals("south", CompanionGeometry.bearingName(180));
        assertEquals("west", CompanionGeometry.bearingName(270));
        assertEquals("north", CompanionGeometry.bearingName(359));
        assertEquals("north-west", CompanionGeometry.bearingName(-45));
    }

    @Test
    @DisplayName("rounding keeps the one decimal the app prints")
    void rounding() {
        assertEquals(1.2, CompanionGeometry.round1(1.24), 0.0001);
        assertEquals(1.3, CompanionGeometry.round1(1.25), 0.0001);
        assertEquals(-0.1, CompanionGeometry.round1(-0.06), 0.0001);
        // Java rounds a half up, so -0.05 lands on 0 rather than -0.1. Pinned so a later change to
        // the helper cannot quietly move a distance the app already displays.
        assertEquals(0.0, CompanionGeometry.round1(-0.05), 0.0001);
    }
}
