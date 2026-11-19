package com.logvex.addon.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walking-speed measurement from successive positions. */
class SpeedTrackerTest {

    @Test
    @DisplayName("the first sample primes the reference and reports no speed yet")
    void firstSamplePrimes() {
        SpeedTracker tracker = new SpeedTracker();
        assertEquals(0.0, tracker.sample(0, 0, 1_000L), 0.0001);
        assertEquals(0.0, tracker.blocksPerSecond(), 0.0001);
    }

    @Test
    @DisplayName("a straight walk of one block in 250 ms reads as four blocks per second")
    void measuresStraightLine() {
        SpeedTracker tracker = new SpeedTracker();
        tracker.sample(0, 0, 1_000L);
        assertEquals(4.0, tracker.sample(1, 0, 1_250L), 0.0001);
    }

    @Test
    @DisplayName("diagonal movement is measured as true distance, not axis-wise")
    void measuresDiagonal() {
        SpeedTracker tracker = new SpeedTracker();
        tracker.sample(0, 0, 1_000L);
        // 3-4-5 triangle over 500 ms: 5 blocks in half a second is 10 blocks per second.
        assertEquals(10.0, tracker.sample(3, 4, 1_500L), 0.0001);
    }

    @Test
    @DisplayName("samples closer together than the minimum interval keep the previous estimate")
    void ignoresTooFrequentSamples() {
        SpeedTracker tracker = new SpeedTracker();
        tracker.sample(0, 0, 1_000L);
        tracker.sample(1, 0, 1_250L);
        assertEquals(4.0, tracker.sample(2, 0, 1_260L), 0.0001);
    }

    @Test
    @DisplayName("a long gap drops the reference instead of reporting a spike")
    void longGapResetsSpeed() {
        SpeedTracker tracker = new SpeedTracker();
        tracker.sample(0, 0, 1_000L);
        tracker.sample(1, 0, 1_250L);
        assertEquals(0.0, tracker.sample(80, 0, 9_000L), 0.0001);
        assertEquals(0.0, tracker.blocksPerSecond(), 0.0001);
    }

    @Test
    @DisplayName("standing still reads as zero, and reset forgets the reference position")
    void standingStillAndReset() {
        SpeedTracker tracker = new SpeedTracker();
        tracker.sample(5, 5, 1_000L);
        assertEquals(0.0, tracker.sample(5, 5, 1_250L), 0.0001);

        tracker.sample(6, 5, 1_500L);
        assertTrue(tracker.blocksPerSecond() > 0.0);
        tracker.reset();
        assertEquals(0.0, tracker.blocksPerSecond(), 0.0001);
        // The next sample primes a new reference rather than measuring from the old one.
        assertEquals(0.0, tracker.sample(50, 50, 1_600L), 0.0001);
    }
}
