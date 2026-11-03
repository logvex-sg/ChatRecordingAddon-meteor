package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.ConflictPolicy;
import com.logvex.addon.schematicdropper.core.PlacementConfig;
import com.logvex.addon.schematicdropper.core.PlacementEngine;
import com.logvex.addon.schematicdropper.core.PlacementFailure;
import com.logvex.addon.schematicdropper.core.PlacementSnapshot;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.PlacementStateMachine;
import com.logvex.addon.schematicdropper.core.PlannedBlock;
import com.logvex.addon.schematicdropper.core.BlockCategory;
import com.logvex.addon.schematicdropper.core.WorldComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for the placement engine. The engine is driven entirely through {@code tick},
 * so these tests step the clock explicitly and assert on verified outcomes only.
 */
class PlacementEngineTest {
    private NewFakes.FakePlacementEnv env;
    private NewFakes.FakePlacementAction actions;
    private NewFakes.RecordingListener listener;
    private NewFakes.FakeTraits traits;
    private PlacementEngine engine;
    private long now;

    @BeforeEach
    void setUp() {
        env = new NewFakes.FakePlacementEnv();
        actions = new NewFakes.FakePlacementAction(env);
        listener = new NewFakes.RecordingListener();
        traits = new NewFakes.FakeTraits();
        engine = new PlacementEngine(env, actions, listener);
        engine.setTraits(traits);
        engine.setConfig(new PlacementConfig(
            1, 0, 3, 0, 1000, 100, 64.0, ConflictPolicy.SKIP, true, false, 1));
        now = 1000;
    }

    private PlannedBlock block(int x, int y, int z, String id, PlacementState state) {
        BlockStateSpec spec = BlockStateSpec.parse(id);
        return new PlannedBlock(new BlockPos3(x, y, z), new BlockPos3(x, y, z), spec, state,
            BlockCategory.SIMPLE, 0, null);
    }

    /** Runs the engine for at most {@code maxTicks} ticks, advancing the clock, until it stops. */
    private void runUntilDone(int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            engine.tick(now);
            if (engine.state() == PlacementStateMachine.COMPLETED
                || engine.state() == PlacementStateMachine.IDLE) return;
            now += 50;
        }
    }

    @Test
    @DisplayName("a simple build places every block and completes")
    void placesSimpleBuild() {
        engine.setPlan(List.of(
            block(0, 0, 0, "minecraft:stone", PlacementState.PENDING),
            block(1, 0, 0, "minecraft:stone", PlacementState.PENDING),
            block(2, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 64);

        engine.start(now);
        runUntilDone(60);

        assertEquals(PlacementStateMachine.COMPLETED, engine.state());
        assertEquals(3, engine.snapshot().completed());
        assertEquals(0, engine.snapshot().failed());
        assertTrue(listener.completed);
        assertEquals(3, listener.placed.size());
    }

    @Test
    @DisplayName("a block the world already holds is marked correct and not placed")
    void skipsAlreadyCorrect() {
        env.world.put(new BlockPos3(1, 0, 0), BlockStateSpec.parse("minecraft:stone"));
        engine.setPlan(List.of(
            block(0, 0, 0, "minecraft:stone", PlacementState.PENDING),
            block(1, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 64);

        engine.start(now);
        runUntilDone(60);

        assertEquals(PlacementStateMachine.COMPLETED, engine.state());
        assertEquals(1, engine.snapshot().completed());
        assertEquals(1, engine.snapshot().alreadyCorrect());
        assertEquals(1, actions.started);
    }

    @Test
    @DisplayName("a placement is only completed after the world confirms it")
    void verifiesBeforeCompleting() {
        actions.pollsBeforeConfirm = 2; // stays pending for two polls
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        engine.tick(now); // PLACING -> begin -> VERIFYING
        assertEquals(PlacementStateMachine.VERIFYING, engine.state());
        assertEquals(0, engine.snapshot().completed(), "must not count an unconfirmed placement");

        engine.tick(now + 10);
        assertEquals(0, engine.snapshot().completed(), "still pending");
        engine.tick(now + 20); // third poll confirms
        engine.tick(now + 30);
        runUntilDone(20);

        assertEquals(PlacementStateMachine.COMPLETED, engine.state());
        assertEquals(1, engine.snapshot().completed());
    }

    @Test
    @DisplayName("a placement the server rejects is retried then given up on")
    void retriesThenFails() {
        actions.rejectAll = true;
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        runUntilDone(200);

        assertEquals(1, engine.snapshot().failed());
        assertEquals(0, engine.snapshot().completed());
        // retryLimit 3 -> three attempts before giving up.
        assertEquals(3, actions.started);
        assertFalse(listener.failures.isEmpty());
    }

    @Test
    @DisplayName("a missing material blocks the block instead of failing it")
    void missingMaterialBlocks() {
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        // No stone in the inventory.

        engine.start(now);
        engine.tick(now);

        assertEquals(PlacementStateMachine.WAITING_FOR_MATERIAL, engine.state());
        assertEquals(0, engine.snapshot().failed());
        assertEquals(0, engine.snapshot().completed());
        assertEquals(0, actions.started);
    }

    @Test
    @DisplayName("adding the material later lets the build continue")
    void resumesWhenMaterialAppears() {
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        engine.start(now);
        engine.tick(now);
        assertEquals(PlacementStateMachine.WAITING_FOR_MATERIAL, engine.state());

        env.inventory.put("minecraft:stone", 1);
        runUntilDone(200);

        assertEquals(PlacementStateMachine.COMPLETED, engine.state());
        assertEquals(1, engine.snapshot().completed());
    }

    @Test
    @DisplayName("a conflict with an unrelated block is skipped, never replaced")
    void conflictIsSkipped() {
        env.world.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:diamond_block"));
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        runUntilDone(60);

        assertEquals(1, engine.snapshot().skipped());
        assertEquals(0, actions.started, "a conflict must not send a placement");
        assertEquals(1, listener.conflicts.size());
        // The diamond block is untouched.
        assertEquals("minecraft:diamond_block", env.world.get(new BlockPos3(0, 0, 0)).id());
    }

    @Test
    @DisplayName("the pause policy pauses the build on a conflict")
    void conflictPauses() {
        engine.setConfig(new PlacementConfig(1, 0, 3, 0, 1000, 100, 64.0,
            ConflictPolicy.PAUSE, true, false, 1));
        env.world.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:diamond_block"));
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        runUntilDone(60);

        assertEquals(PlacementStateMachine.PAUSED, engine.state());
    }

    @Test
    @DisplayName("an out-of-range position fails with the range reason")
    void outOfRange() {
        env.range = 1.0;
        engine.setConfig(new PlacementConfig(1, 0, 1, 0, 1000, 100, 1.0,
            ConflictPolicy.SKIP, true, false, 1));
        engine.setPlan(List.of(block(500, 0, 500, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        runUntilDone(60);

        assertEquals(1, engine.snapshot().failed());
        assertTrue(listener.failures.get(0).contains("OUT_OF_RANGE"), listener.failures.toString());
        assertEquals(0, actions.started);
    }

    @Test
    @DisplayName("an unloaded chunk fails the block rather than guessing")
    void unloadedChunkFails() {
        env.unloaded.add(new BlockPos3(0, 0, 0));
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        runUntilDone(60);

        assertEquals(1, engine.snapshot().failed());
        assertTrue(listener.failures.get(0).contains("CHUNK_UNLOADED"), listener.failures.toString());
    }

    @Test
    @DisplayName("pausing mid-verify waits for the in-flight placement")
    void pauseWaitsForPending() {
        actions.pollsBeforeConfirm = 3;
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);

        engine.start(now);
        engine.tick(now); // now VERIFYING
        engine.pause();
        assertEquals(PlacementStateMachine.VERIFYING, engine.state(), "must finish the in-flight placement");

        // Drive the pending placement to confirmation; the engine may then pause.
        for (int i = 0; i < 10 && engine.state() != PlacementStateMachine.COMPLETED; i++) {
            engine.tick(now + i * 10L);
        }
        assertTrue(engine.state() == PlacementStateMachine.COMPLETED
            || engine.state() == PlacementStateMachine.PAUSED);
    }

    @Test
    @DisplayName("stopping clears the plan's in-flight work and returns to idle")
    void stopReturnsToIdle() {
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);
        engine.start(now);
        engine.tick(now);

        engine.stop();

        assertEquals(PlacementStateMachine.IDLE, engine.state());
        // The plan is kept for inspection, but a stopped engine must do no more work.
        int startedBefore = actions.started;
        engine.tick(now + 500);
        engine.tick(now + 1000);
        assertEquals(startedBefore, actions.started, "a stopped engine must not place anything");
    }

    @Test
    @DisplayName("a world change resets the run without reporting completion")
    void worldChangeResets() {
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);
        engine.start(now);
        engine.tick(now);

        engine.onWorldChanged();

        assertEquals(PlacementStateMachine.IDLE, engine.state());
        assertFalse(listener.completed);
    }

    @Test
    @DisplayName("the engine stops when no player or world exists")
    void noEnvironmentStops() {
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);
        engine.start(now);
        env.canRun = false;

        engine.tick(now);
        engine.tick(now + 1000);

        assertEquals(0, engine.snapshot().completed());
        assertEquals(0, actions.started);
    }

    @Test
    @DisplayName("a failed block can be retried explicitly")
    void retryFailedBlock() {
        actions.rejectAll = true;
        engine.setPlan(List.of(block(0, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 1);
        engine.start(now);
        runUntilDone(200);
        assertEquals(1, engine.snapshot().failed());

        // The server recovers.
        actions.rejectAll = false;
        actions.started = 0;
        engine.retryAllFailed();
        runUntilDone(200);

        assertEquals(PlacementStateMachine.COMPLETED, engine.state());
        assertEquals(1, engine.snapshot().completed());
    }

    @Test
    @DisplayName("the snapshot reports progress that never exceeds 100%")
    void snapshotPercentBounded() {
        engine.setPlan(List.of(
            block(0, 0, 0, "minecraft:stone", PlacementState.PENDING),
            block(1, 0, 0, "minecraft:stone", PlacementState.PENDING)));
        env.inventory.put("minecraft:stone", 64);
        engine.start(now);
        runUntilDone(60);

        PlacementSnapshot snapshot = engine.snapshot();
        assertTrue(snapshot.percent() <= 100.0);
        assertEquals(0, snapshot.remaining());
        assertFalse(snapshot.hasErrors());
    }

    @Test
    @DisplayName("an empty plan completes immediately without touching the world")
    void emptyPlan() {
        engine.setPlan(List.of());
        engine.start(now);
        engine.tick(now);

        assertEquals(0, actions.started);
        assertNotNull(engine.snapshot());
    }
}
