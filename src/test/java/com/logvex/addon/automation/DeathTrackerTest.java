package com.logvex.addon.automation;

import com.logvex.addon.automation.core.DeathTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The death history: order, the bound, and per-dimension lookup.
 *
 * <p>The per-dimension lookup is the part worth testing. Coordinates only mean something in the
 * dimension they were recorded in, so returning a death from another world would be worse than
 * returning nothing.
 */
class DeathTrackerTest {

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("the newest death is the last one")
        void lastIsNewest() {
            DeathTracker tracker = new DeathTracker();
            tracker.record("minecraft:overworld", 1, 2, 3, 1_000, "");
            tracker.record("minecraft:overworld", 4, 5, 6, 2_000, "");
            assertEquals("4, 5, 6", tracker.last().coords());
        }

        @Test
        @DisplayName("recent is newest first")
        void recentNewestFirst() {
            DeathTracker tracker = new DeathTracker();
            tracker.record("minecraft:overworld", 1, 2, 3, 1_000, "");
            tracker.record("minecraft:overworld", 4, 5, 6, 2_000, "");
            List<DeathTracker.Death> recent = tracker.recent();
            assertEquals(2, recent.size());
            assertEquals("4, 5, 6", recent.get(0).coords());
            assertEquals("1, 2, 3", recent.get(1).coords());
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("the oldest entry is dropped when full")
        void dropsOldest() {
            DeathTracker tracker = new DeathTracker(2);
            tracker.record("w", 1, 1, 1, 1_000, "");
            tracker.record("w", 2, 2, 2, 2_000, "");
            tracker.record("w", 3, 3, 3, 3_000, "");
            assertEquals(2, tracker.size());
            assertEquals("3, 3, 3", tracker.last().coords());
        }

        @Test
        @DisplayName("an empty tracker reports nothing")
        void empty() {
            DeathTracker tracker = new DeathTracker();
            assertNull(tracker.last());
            assertNull(tracker.lastIn("minecraft:overworld"));
            assertEquals(0, tracker.recent().size());
        }
    }

    @Nested
    @DisplayName("per dimension")
    class PerDimension {

        @Test
        @DisplayName("the lookup skips deaths in other dimensions")
        void skipsOtherDimensions() {
            DeathTracker tracker = new DeathTracker();
            tracker.record("minecraft:overworld", 1, 2, 3, 1_000, "");
            tracker.record("minecraft:the_nether", 7, 8, 9, 2_000, "");
            DeathTracker.Death overworld = tracker.lastIn("minecraft:overworld");
            assertNotNull(overworld);
            assertEquals("1, 2, 3", overworld.coords(),
                "the nether death is newer but its coordinates mean nothing in the overworld");
        }

        @Test
        @DisplayName("a dimension with no deaths reports null")
        void noneInDimension() {
            DeathTracker tracker = new DeathTracker();
            tracker.record("minecraft:overworld", 1, 2, 3, 1_000, "");
            assertNull(tracker.lastIn("minecraft:the_end"));
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("clear empties the history")
        void clears() {
            DeathTracker tracker = new DeathTracker();
            tracker.record("w", 1, 2, 3, 1_000, "");
            tracker.clear();
            assertEquals(0, tracker.size());
            assertNull(tracker.last());
        }
    }
}
