package com.logvex.addon.framework.gui.animation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The animation primitives the widgets ease their hover, selection and toggle states with.
 *
 * <p>An animator that never settles would burn a frame budget forever, and one that overshoots its
 * bounds would draw outside the widget. These tests pin down both, plus the shape of the easing
 * curves the interface depends on for its "feel".
 */
class AnimationTest {

    @Test
    @DisplayName("every easing curve is pinned to 0 at the start and 1 at the end")
    void easingEndpoints() {
        double[] starts = {
            Easing.linear(0), Easing.quadIn(0), Easing.quadOut(0), Easing.quadInOut(0),
            Easing.cubicOut(0), Easing.sineOut(0), Easing.backOut(0), Easing.elasticOut(0)
        };
        for (double v : starts) assertEquals(0, v, 1e-9, "an easing curve must start at 0");

        double[] ends = {
            Easing.linear(1), Easing.quadIn(1), Easing.quadOut(1), Easing.quadInOut(1),
            Easing.cubicOut(1), Easing.sineOut(1), Easing.backOut(1), Easing.elasticOut(1)
        };
        for (double v : ends) assertEquals(1, v, 1e-9, "an easing curve must end at 1");
    }

    @Test
    @DisplayName("easing curves clamp values outside 0..1 instead of extrapolating")
    void easingClamps() {
        assertEquals(0, Easing.quadOut(-3), 1e-9);
        assertEquals(1, Easing.quadOut(3), 1e-9);
        assertEquals(0, Easing.cubicOut(-1), 1e-9);
        assertEquals(1, Easing.cubicOut(2), 1e-9);
    }

    @Test
    @DisplayName("a decelerating curve is ahead of linear at the midpoint")
    void deceleratingCurvesLeadEarly() {
        // The point of an "out" curve is that it covers most of the distance early and eases at the
        // end; if it did not, the interface would feel sluggish.
        assertTrue(Easing.quadOut(0.5) > 0.5);
        assertTrue(Easing.cubicOut(0.5) > 0.5);
        assertTrue(Easing.sineOut(0.5) > 0.5);
    }

    @Test
    @DisplayName("an accelerating curve lags linear at the midpoint")
    void acceleratingCurveLags() {
        assertTrue(Easing.quadIn(0.5) < 0.5);
    }

    @Test
    @DisplayName("backOut overshoots past 1 before it settles")
    void backOutOvershoots() {
        boolean overshot = false;
        for (int i = 0; i <= 100; i++) {
            if (Easing.backOut(i / 100.0) > 1.0) overshot = true;
        }
        assertTrue(overshot, "backOut is defined by its overshoot");
    }

    @Test
    @DisplayName("an animator reaches its target and then reports settled")
    void animatorSettles() {
        Animator animator = new Animator(0, 0.1, Easing::linear);
        animator.target(1);
        assertFalse(animator.settled());

        // Step past the duration in one go.
        animator.update(0.2);
        assertEquals(1, animator.value(), 1e-9);
        assertTrue(animator.settled(), "an animator at its target must not keep animating");
    }

    @Test
    @DisplayName("an animator interpolates across the duration rather than jumping")
    void animatorInterpolates() {
        Animator animator = new Animator(0, 1.0, Easing::linear);
        animator.target(10);
        animator.update(0.5);
        assertEquals(5, animator.value(), 1e-9, "half the duration should be half the distance with linear");
        assertFalse(animator.settled());
    }

    @Test
    @DisplayName("retargeting mid-flight keeps the current value as the new start")
    void retargetKeepsCurrent() {
        Animator animator = new Animator(0, 1.0, Easing::linear);
        animator.target(10);
        animator.update(0.5); // now at 5
        animator.target(0);
        animator.update(0.25); // quarter of the way from 5 back to 0
        assertEquals(3.75, animator.value(), 1e-9, "a reversal must not snap back to the old start");
    }

    @Test
    @DisplayName("snap jumps straight to a value and reports settled")
    void snapIsImmediate() {
        Animator animator = new Animator(0, 1.0, Easing::linear);
        animator.snap(7);
        assertEquals(7, animator.value(), 1e-9);
        assertTrue(animator.settled());
        animator.update(0.5);
        assertEquals(7, animator.value(), 1e-9, "a settled animator must not move");
    }

    @Test
    @DisplayName("a settled animator ignores an update and a zero duration is instant")
    void degenerateCases() {
        Animator zero = new Animator(0, 0, Easing::linear);
        zero.target(5);
        zero.update(0.016);
        assertEquals(5, zero.value(), 1e-9, "a zero-duration animation must be instantaneous");

        Animator animator = Animator.of(0);
        animator.update(1.0);
        assertEquals(0, animator.value(), 1e-9, "with no target an animator must not drift");
    }

    @Test
    @DisplayName("the clock reports a usable delta and never zero")
    void clockDeltaIsUsable() {
        AnimationClock.reset();
        AnimationClock.tick();
        double delta = AnimationClock.delta();
        assertTrue(delta > 0, "the clock must never report a zero delta");
        assertTrue(delta <= 0.1, "the clock must clamp a stall so an animation cannot jump");
    }
}
