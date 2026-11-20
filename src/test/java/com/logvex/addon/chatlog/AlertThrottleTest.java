package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.AlertThrottle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The throttle is what stops a mention alert becoming a flood.
 *
 * <p>A watched name used in every message, or a server where many names are hit at once, would
 * otherwise print a chat line and play a sound for each one.
 */
class AlertThrottleTest {

    @Test
    @DisplayName("the first alert fires and an immediate repeat is folded")
    void repeatIsFolded() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("Steve", 100));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("Steve", 200));
        assertEquals(2, throttle.foldedCount("Steve"));
    }

    @Test
    @DisplayName("the folded count is reported once and then cleared")
    void foldedCountIsReportedOnce() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        throttle.admit("Steve", 0);
        throttle.admit("Steve", 100);
        throttle.admit("Steve", 200);

        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 2_000));
        assertEquals(2, throttle.takeFolded("Steve"));
        assertEquals(0, throttle.takeFolded("Steve"), "the count must not be reported twice");
    }

    @Test
    @DisplayName("a burst of a thousand lines produces one alert, not a thousand")
    void thousandMentionsInOneSecondProduceOneAlert() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        int fired = 0;
        for (int i = 0; i < 1_000; i++) {
            if (throttle.admit("Steve", 0) == AlertThrottle.Verdict.FIRE) fired++;
        }
        assertEquals(1, fired);
    }

    @Test
    @DisplayName("different names are throttled independently")
    void namesAreIndependent() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Alex", 0));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("Steve", 10));
    }

    @Test
    @DisplayName("the burst cap stops many different names alerting at once")
    void burstCapLimitsDistinctNames() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        int fired = 0;
        for (int i = 0; i < 100; i++) {
            if (throttle.admit("name" + i, 0) == AlertThrottle.Verdict.FIRE) fired++;
        }
        assertEquals(6, fired, "the burst cap must hold even for distinct keys");
    }

    @Test
    @DisplayName("the burst cap refills once the window passes")
    void burstCapRefills() {
        AlertThrottle throttle = new AlertThrottle(0, 2, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("a", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("b", 0));
        assertEquals(AlertThrottle.Verdict.DROP, throttle.admit("c", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("c", 10_001));
    }

    @Test
    @DisplayName("a zero cooldown disables per-name throttling")
    void zeroCooldownDisables() {
        AlertThrottle throttle = new AlertThrottle(0, 0, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
    }

    @Test
    @DisplayName("a long gap lets the same name alert again")
    void cooldownExpires() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 2_001));
        assertEquals(0, throttle.takeFolded("Steve"));
    }

    @Test
    @DisplayName("keys are matched case-insensitively")
    void keysAreCaseInsensitive() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("steve", 10));
    }

    @Test
    @DisplayName("retain drops state for names no longer watched")
    void retainDropsStaleNames() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        throttle.admit("Steve", 0);
        throttle.admit("Alex", 0);
        throttle.retain(List.of("Steve"));

        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("Steve", 10));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Alex", 10),
            "a name that is no longer watched must not carry its old cooldown");
    }

    @Test
    @DisplayName("pruning every tick does not reset the burst cap")
    void retainDoesNotResetBurstCap() {
        // The modules call retain() on every tick with the same list. If that cleared the burst
        // window the cap would never apply, and a flood would get through.
        AlertThrottle throttle = new AlertThrottle(0, 3, 10_000);
        List<String> watch = List.of("a", "b", "c", "d");
        int fired = 0;
        for (int i = 0; i < 20; i++) {
            throttle.retain(watch);
            if (throttle.admit("name" + i, 0) == AlertThrottle.Verdict.FIRE) fired++;
        }
        assertEquals(3, fired, "the burst cap must hold across ticks");
    }

    @Test
    @DisplayName("a removed name loses its cooldown, a kept one keeps it")
    void retainPrunesOnlyRemovedNames() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 0));
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Alex", 0));
        throttle.retain(List.of("Steve"));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("Steve", 10),
            "a name still watched keeps its cooldown");
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Alex", 10),
            "a removed name must not carry its old cooldown");
    }

    @Test
    @DisplayName("reset clears everything")
    void resetClears() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        throttle.admit("Steve", 0);
        throttle.admit("Steve", 10);
        throttle.reset();
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit("Steve", 20));
        assertEquals(0, throttle.foldedCount("Steve"));
    }

    @Test
    @DisplayName("a null or blank key does not throw")
    void nullKeyIsSafe() {
        AlertThrottle throttle = new AlertThrottle(2_000, 6, 10_000);
        assertEquals(AlertThrottle.Verdict.FIRE, throttle.admit(null, 0));
        assertEquals(AlertThrottle.Verdict.FOLD, throttle.admit("", 10));
    }
}
