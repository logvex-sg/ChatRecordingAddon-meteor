package com.logvex.addon.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick threat cache.
 *
 * <p>The behaviour under test is not the scan itself — the Minecraft layer owns that — but the
 * policy around it: how often the world is asked, and whether a cached answer can ever be served
 * where a fresh one was needed. The second question is the safety-critical one, because this
 * answer decides whether the bot pauses next to a hostile.
 */
class HostileScanTest {

    /** A query that counts its calls and reports a fixed nearest distance. */
    private static final class Counting implements HostileScan.Query {
        final AtomicInteger calls = new AtomicInteger();
        final double nearest;
        final int count;

        Counting(double nearest, int count) {
            this.nearest = nearest;
            this.count = count;
        }

        @Override
        public HostileScan.Hit nearest(double x, double y, double z, double radius) {
            calls.incrementAndGet();
            return count == 0 ? HostileScan.NONE : new HostileScan.Hit(nearest, count);
        }
    }

    @Test
    void oneTickScansOnceNoMatterHowManyCallers() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        // The tick asks three times: auto-eat, the safety gate, then the HUD.
        for (int i = 0; i < 3; i++) {
            assertTrue(scan.anyWithin(100, "overworld", 0, 64, 0, 8, query));
        }

        assertEquals(1, query.calls.get(), "three callers in one tick must share one scan");
    }

    @Test
    void theNextTickScansAgain() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        scan.anyWithin(100, "overworld", 0, 64, 0, 8, query);
        scan.anyWithin(101, "overworld", 0, 64, 0, 8, query);

        assertEquals(2, query.calls.get(), "a new tick must not reuse the old answer");
    }

    @Test
    void aDifferentWorldIsNeverServedFromCache() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        scan.anyWithin(100, "overworld", 0, 64, 0, 8, query);
        scan.anyWithin(100, "nether", 0, 64, 0, 8, query);

        assertEquals(2, query.calls.get(), "leaving the dimension must rescan");
    }

    @Test
    void aMovedCentreRescansRatherThanEstimating() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        scan.anyWithin(100, "overworld", 0, 64, 0, 8, query);
        // Half a block away: close enough that an estimate would be tempting, and wrong to make.
        scan.anyWithin(100, "overworld", 0.5, 64, 0, 8, query);

        assertEquals(2, query.calls.get(), "a moved eye position must not reuse an estimate");
    }

    @Test
    void aWideScanAnswersANarrowQuestion() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(10, 1);

        // The first caller asks for 12 blocks, so the scan reached far enough for 8.
        assertTrue(scan.anyWithin(100, "overworld", 0, 64, 0, 12, query));
        assertFalse(scan.anyWithin(100, "overworld", 0, 64, 0, 8, query));

        assertEquals(1, query.calls.get(), "nearest distance is monotonic in radius, so one scan serves both");
    }

    @Test
    void aNarrowScanDoesNotAnswerAWideQuestion() {
        HostileScan scan = new HostileScan();
        // A hostile at 10 blocks: outside a 2-block question, inside a 12-block one.
        Counting query = new Counting(10, 1);

        scan.anyWithin(100, "overworld", 0, 64, 0, 2, query);
        // The 2-block scan still reached 2 + slack, which is not enough to answer 12.
        assertTrue(scan.anyWithin(100, "overworld", 0, 64, 0, 12, query));

        assertEquals(2, query.calls.get(), "a short scan must not answer a longer question");
    }

    @Test
    void anEmptyScanReportsNoThreatAtAnyRadius() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(0, 0);

        assertFalse(scan.anyWithin(100, "overworld", 0, 64, 0, 64, query));
        assertEquals(Double.POSITIVE_INFINITY, scan.nearest(100, "overworld", 0, 64, 0, 8, query).distance());
    }

    @Test
    void aNullQueryIsTreatedAsNoThreatRatherThanACrash() {
        HostileScan scan = new HostileScan();
        assertEquals(HostileScan.NONE, scan.nearest(100, "overworld", 0, 64, 0, 8, null));
    }

    @Test
    void clearDropsEverything() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        scan.anyWithin(100, "overworld", 0, 64, 0, 8, query);
        scan.clear();
        scan.anyWithin(100, "overworld", 0, 64, 0, 8, query);

        assertEquals(2, query.calls.get());
    }

    @Test
    void theCacheCannotGrowWithoutBound() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(3, 1);

        // A pathological tick where the eye position moves every call.
        for (int i = 0; i < 100; i++) {
            scan.anyWithin(100, "overworld", i, 64, 0, 8, query);
        }

        assertTrue(scan.size() <= 8, "the cache must stay bounded, was " + scan.size());
    }

    @Test
    void countIsReportedForDisplay() {
        HostileScan scan = new HostileScan();
        Counting query = new Counting(2.5, 4);

        HostileScan.Hit hit = scan.nearest(100, "overworld", 0, 64, 0, 8, query);
        assertEquals(4, hit.count());
        assertEquals(2.5, hit.distance());
        assertFalse(hit.isEmpty());
    }
}
