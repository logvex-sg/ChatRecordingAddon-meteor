package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ActivityHistogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A time jump must not cost memory proportional to the jump.
 *
 * <p>Buckets are meant to be bounded by the configured capacity, but a record calls that lands
 * years after the previous one used to materialise every empty bucket in between before trimming
 * them, which exhausted the heap. A clock change, a laptop resuming from sleep, or a log replayed
 * with old timestamps all produce that jump.
 */
class ActivityHistogramJumpTest {

    private static final long MINUTE = 60_000L;

    @Test
    void aJumpOfYearsCostsNoMoreThanTheCapacity() {
        ActivityHistogram histogram = new ActivityHistogram(MINUTE, 60);

        histogram.record(0);
        // Three years later. At one bucket per minute this is about 1.6 million buckets.
        histogram.record(3L * 365 * 24 * 60 * MINUTE);

        assertTrue(histogram.recent(1_000).size() <= 60,
            "the window must never hold more than its capacity");
    }

    @Test
    void aJumpForwardsStillCountsTheNewMessage() {
        ActivityHistogram histogram = new ActivityHistogram(MINUTE, 60);

        histogram.record(0);
        histogram.record(1_000 * MINUTE);
        histogram.record(1_000 * MINUTE);

        // The latest bucket is the one the message landed in, and it holds both of the two
        // messages recorded at that time.
        ActivityHistogram.Bucket last = histogram.recent(1).get(0);
        assertEquals(1_000 * MINUTE, last.start());
        assertEquals(2, last.count());
    }

    @Test
    void aJumpBackwardsDoesNotTravelBackInTime() {
        ActivityHistogram histogram = new ActivityHistogram(MINUTE, 60);

        histogram.record(1_000 * MINUTE);
        // A clock that steps backwards, or a replay of an older log.
        histogram.record(0);

        assertEquals(1, histogram.recent(1).size(), "one bucket is all that was recorded");
        assertEquals(1_000 * MINUTE, histogram.recent(1).get(0).start(),
            "a bucket older than the window head must not be inserted behind it");
    }

    @Test
    void theJumpIsCheapEnoughToRunOnTheClientThread() {
        ActivityHistogram histogram = new ActivityHistogram(MINUTE, 120);
        histogram.record(0);
        // Filling a bucket per minute for twenty years is over ten million of them. Materialising
        // them one at a time took seconds and ran out of heap; the window is 120 buckets wide.
        long start = System.nanoTime();
        histogram.record(20L * 365 * 24 * 60 * MINUTE);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 100, "a clock jump took " + millis + " ms, which would stall a frame");
        assertEquals(120, histogram.recent(1_000).size(), "the window fills to its capacity");
    }
}
