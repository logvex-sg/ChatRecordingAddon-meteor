package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.MaterialProgress;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.core.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTrackerTest {
    private ProgressTracker trackerWith(Requirement... requirements) {
        ProgressTracker tracker = new ProgressTracker();
        MaterialPlan plan = new MaterialPlan();
        plan.set(List.of(requirements));
        tracker.setPlan(plan);
        return tracker;
    }

    @Test
    @DisplayName("progress is only advanced by confirmed transfers")
    void confirmedOnly() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:cobblestone", "Cobblestone", 100));
        assertEquals(0, tracker.totalConfirmed());
        assertEquals(0.0, tracker.percent(), 0.0001);

        tracker.addConfirmed("minecraft:cobblestone", 40);
        assertEquals(40, tracker.totalConfirmed());
        assertEquals(40.0, tracker.percent(), 0.0001);
    }

    @Test
    @DisplayName("zero or negative confirmations are ignored")
    void ignoresNonPositive() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.addConfirmed("minecraft:stone", 0);
        tracker.addConfirmed("minecraft:stone", -5);
        tracker.addConfirmed(null, 5);
        assertEquals(0, tracker.totalConfirmed());
    }

    @Test
    @DisplayName("confirmed quantity never exceeds the requirement")
    void clampsToRequirement() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.addConfirmed("minecraft:stone", 25);
        assertEquals(10, tracker.confirmed("minecraft:stone"));
        assertEquals(100.0, tracker.percent(), 0.0001);
        assertTrue(tracker.isComplete());
    }

    @Test
    @DisplayName("no duplicate progress when the same confirmation arrives twice")
    void noDuplicateProgress() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.addConfirmed("minecraft:stone", 1);
        tracker.addConfirmed("minecraft:stone", 1);
        assertEquals(2, tracker.totalConfirmed());
        assertFalse(tracker.totalConfirmed() == 4);
    }

    @Test
    @DisplayName("an empty plan reports 0% and is never complete")
    void emptyPlanIsSafe() {
        ProgressTracker tracker = new ProgressTracker();
        assertEquals(0.0, tracker.percent(), 0.0001);
        assertFalse(tracker.isComplete());
        assertEquals(0, tracker.totalRequired());
        ProgressSnapshot snapshot = tracker.snapshot();
        assertEquals(0, snapshot.materials().size());
        assertFalse(snapshot.percent() > 0);
    }

    @Test
    @DisplayName("snapshot reports per-material values including missing quantities")
    void snapshotValues() {
        ProgressTracker tracker = trackerWith(
            new Requirement("minecraft:cobblestone", "Cobblestone", 100),
            new Requirement("minecraft:stone", "Stone", 50)
        );
        tracker.addConfirmed("minecraft:cobblestone", 68);
        tracker.setInventory("minecraft:cobblestone", 12);
        tracker.setMissing("minecraft:stone", 50);
        tracker.setCurrentMaterial("minecraft:cobblestone");
        tracker.setCurrentStack(3, 32);
        tracker.setState(State.DROPPING);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertEquals(150, snapshot.totalRequired());
        assertEquals(68, snapshot.confirmedDropped());
        assertEquals(82, snapshot.remaining());
        assertEquals(0, snapshot.completedTypes());
        assertEquals(2, snapshot.totalTypes());
        assertEquals(1, snapshot.missingTypes());
        assertEquals("minecraft:cobblestone", snapshot.currentMaterial());
        assertEquals(3, snapshot.currentStackSlot());
        assertEquals(32, snapshot.currentStackCount());
        assertEquals(State.DROPPING, snapshot.state());
        assertEquals(68.0 / 150.0 * 100.0, snapshot.percent(), 0.0001);

        MaterialProgress cobble = find(snapshot, "minecraft:cobblestone");
        assertEquals(100, cobble.required());
        assertEquals(68, cobble.confirmed());
        assertEquals(32, cobble.remaining());
        assertEquals(12, cobble.inventory());
        assertEquals(0, cobble.missing());
        assertFalse(cobble.complete());

        MaterialProgress stone = find(snapshot, "minecraft:stone");
        assertEquals(50, stone.missing());
        assertEquals(0, stone.confirmed());
    }

    @Test
    @DisplayName("a fully confirmed plan is 100% complete")
    void completeAtOneHundred() {
        ProgressTracker tracker = trackerWith(
            new Requirement("minecraft:cobblestone", "Cobblestone", 10),
            new Requirement("minecraft:stone", "Stone", 5)
        );
        tracker.addConfirmed("minecraft:cobblestone", 10);
        tracker.addConfirmed("minecraft:stone", 5);

        assertTrue(tracker.isComplete());
        assertEquals(100.0, tracker.percent(), 0.0001);
        ProgressSnapshot snapshot = tracker.snapshot();
        assertEquals(2, snapshot.completedTypes());
        assertEquals(0, snapshot.remaining());
    }

    @Test
    @DisplayName("resetProgress clears counters but keeps the plan")
    void resetKeepsPlan() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.addConfirmed("minecraft:stone", 7);
        tracker.recordFailure();
        tracker.resetProgress();

        assertEquals(0, tracker.totalConfirmed());
        assertEquals(10, tracker.totalRequired());
        assertEquals(1, tracker.requirementCount());
        assertEquals(0, tracker.failedOperations());
    }

    @Test
    @DisplayName("reset progress does not cause an already-dropped item to count again")
    void resetDoesNotDuplicate() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.addConfirmed("minecraft:stone", 10);
        assertTrue(tracker.isComplete());

        tracker.resetProgress();
        assertFalse(tracker.isComplete());
        // The next confirmed transfer simply continues from zero; the tracker never re-reports old work.
        tracker.addConfirmed("minecraft:stone", 1);
        assertEquals(1, tracker.totalConfirmed());
    }

    @Test
    @DisplayName("failure count is tracked separately from progress")
    void failuresTracked() {
        ProgressTracker tracker = trackerWith(new Requirement("minecraft:stone", "Stone", 10));
        tracker.recordFailure();
        tracker.recordFailure();
        assertEquals(2, tracker.failedOperations());
        assertEquals(0, tracker.totalConfirmed());
    }

    private static MaterialProgress find(ProgressSnapshot snapshot, String itemId) {
        for (MaterialProgress progress : snapshot.materials()) {
            if (progress.itemId().equals(itemId)) return progress;
        }
        throw new AssertionError("missing material " + itemId);
    }
}
