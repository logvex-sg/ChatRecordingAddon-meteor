package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.RotationMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationMathTest {
    private static final float EPS = 0.01f;

    @Test
    @DisplayName("yaw points along the cardinal directions")
    void yawCardinals() {
        // Minecraft yaw: 0 = south (+Z), 90 = west (-X), 180 = north (-Z), -90 = east (+X).
        assertEquals(0f, RotationMath.yawTo(0, 0, 0, 10), EPS);
        assertEquals(180f, Math.abs(RotationMath.yawTo(0, 0, 0, -10)), EPS);
        assertEquals(90f, RotationMath.yawTo(0, 0, -10, 0), EPS);
        assertEquals(-90f, RotationMath.yawTo(0, 0, 10, 0), EPS);
    }

    @Test
    @DisplayName("pitch is positive when looking down")
    void pitchSign() {
        assertTrue(RotationMath.pitchTo(0, 10, 10) < 0, "target above the eye looks up");
        assertTrue(RotationMath.pitchTo(0, -10, 10) > 0, "target below the eye looks down");
        assertEquals(0f, RotationMath.pitchTo(0, 0, 10), EPS);
        assertEquals(-90f, RotationMath.pitchTo(0, 10, 0), EPS);
        assertEquals(90f, RotationMath.pitchTo(0, -10, 0), EPS);
    }

    @Test
    @DisplayName("pitch is clamped to the legal range")
    void pitchClamped() {
        float straightUp = RotationMath.pitchTo(0, 1000, 0.0001);
        assertTrue(straightUp >= -90f && straightUp <= 90f);
    }

    @Test
    @DisplayName("wrap normalises angles into (-180, 180]")
    void wrapping() {
        assertEquals(0f, RotationMath.wrap(0), EPS);
        assertEquals(180f, RotationMath.wrap(180), EPS);
        assertEquals(-179f, RotationMath.wrap(181), EPS);
        assertEquals(179f, RotationMath.wrap(-181), EPS);
        assertEquals(10f, RotationMath.wrap(370), EPS);
    }

    @Test
    @DisplayName("delta takes the shortest way around the circle")
    void deltaShortestPath() {
        assertEquals(10f, RotationMath.delta(0, 10), EPS);
        assertEquals(-20f, RotationMath.delta(170, 150), EPS);
        assertEquals(20f, RotationMath.delta(-170, -150), EPS);
        assertEquals(10f, RotationMath.delta(175, -175), EPS);
        assertEquals(-10f, RotationMath.delta(-175, 175), EPS);
    }

    @Test
    @DisplayName("stepToward converges without overshooting")
    void stepping() {
        assertEquals(10f, RotationMath.stepToward(0, 30, 10), EPS);
        assertEquals(30f, RotationMath.stepToward(0, 30, 100), EPS);
        assertEquals(-10f, RotationMath.stepToward(0, -30, 10), EPS);
    }

    @Test
    @DisplayName("stepToward crosses the wrap boundary the short way")
    void steppingAcrossWrap() {
        float next = RotationMath.stepToward(175, -175, 5);
        assertTrue(RotationMath.within(next, -175f, 5.01f), "stepped the short way, got " + next);
        assertFalse(RotationMath.within(next, 175f, 0.001f));
    }

    @Test
    @DisplayName("lerpDegrees interpolates around the short way")
    void interpolation() {
        assertEquals(0f, RotationMath.lerpDegrees(0, 90, 0f), EPS);
        assertEquals(90f, RotationMath.lerpDegrees(0, 90, 1f), EPS);
        assertEquals(45f, RotationMath.lerpDegrees(0, 90, 0.5f), EPS);
        // From 170 to -170 the short way goes through 180.
        assertEquals(180f, RotationMath.lerpDegrees(170, -170, 0.5f), EPS);
    }

    @Test
    @DisplayName("rotationTo is finite for valid coordinates and never returns NaN")
    void finiteRotations() {
        var r = RotationMath.rotationTo(0, 64, 0, 10, 64, 10);
        assertTrue(RotationMath.isFinite(r.yaw()));
        assertTrue(RotationMath.isFinite(r.pitch()));

        var same = RotationMath.rotationTo(5, 5, 5, 5, 5, 5);
        assertTrue(RotationMath.isFinite(same.yaw()));
        assertTrue(RotationMath.isFinite(same.pitch()));
    }
}
