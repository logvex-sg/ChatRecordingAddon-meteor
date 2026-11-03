package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.AcquisitionConfig;
import com.logvex.addon.schematicdropper.core.AcquisitionEngine;
import com.logvex.addon.schematicdropper.core.AcquisitionListener;
import com.logvex.addon.schematicdropper.core.AcquisitionSnapshot;
import com.logvex.addon.schematicdropper.core.AcquisitionState;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.MaterialAcquisition;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real acquisition state machine with fake ports. The fake inventory is changed only
 * when a give is confirmed, which mirrors how a real server behaves.
 */
class AcquisitionEngineTest {

    private static final class Harness {
        final AcquisitionFakes.FakeEnv env = new AcquisitionFakes.FakeEnv();
        final AcquisitionFakes.FakeActions actions = new AcquisitionFakes.FakeActions();
        final AcquisitionFakes.FakeDrain drain = new AcquisitionFakes.FakeDrain();
        final AcquisitionEngine engine;
        final List<AcquisitionState> states = new ArrayList<>();
        final List<FailureType> failures = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        final List<String> acquired = new ArrayList<>();
        boolean fatalSeen;
        int completions;

        long now = 1_000_000L;

        Harness() {
            engine = new AcquisitionEngine(env, actions, drain, new AcquisitionListener() {
                @Override
                public void onStateChanged(AcquisitionState state) {
                    states.add(state);
                }

                @Override
                public void onAcquired(String itemId, int amount) {
                    acquired.add(itemId + "x" + amount);
                    // Mirror the server: a confirmed give really places the items.
                    AcquisitionFakes.FakeEnv target = env;
                    for (int i = 0; i < AcquisitionFakes.FakeEnv.SLOTS; i++) {
                        if (itemId.equals(target.slotItem[i]) || target.slotItem[i] == null) {
                            target.applyGive(itemId, i, target.slotCount[i] + amount);
                            return;
                        }
                    }
                }

                @Override
                public void onFailure(FailureType type, String detail, boolean fatal) {
                    failures.add(type);
                    if (fatal) fatalSeen = true;
                }

                @Override
                public void onCompleted(int materials, long totalAcquired, long elapsedMs) {
                    completions++;
                }

                @Override
                public void onFeedback(String message) {
                    feedback.add(message);
                }
            });
            engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        }

        void plan(Requirement... requirements) {
            MaterialPlan plan = new MaterialPlan();
            plan.set(List.of(requirements));
            engine.setPlan(plan);
        }

        void start() {
            engine.start(now);
        }

        void tick(long deltaMs) {
            now += deltaMs;
            engine.tick(now);
        }

        void run(int ticks, long deltaMs) {
            for (int i = 0; i < ticks; i++) tick(deltaMs);
        }

        /** Ticks until the engine reaches one of the targets, so state parity cannot matter. */
        void runUntil(java.util.Set<AcquisitionState> targets, int maxTicks) {
            for (int i = 0; i < maxTicks; i++) {
                tick(10);
                if (targets.contains(engine.state())) return;
            }
        }

        AcquisitionState state() {
            return engine.state();
        }
    }

    // ---------------------------------------------------------------- basics

    @Test
    @DisplayName("acquires a single item and completes")
    void singleItem() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:stone", "Stone", 32));
        h.start();
        h.run(4, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(1, h.completions);
        assertEquals(32, h.env.countOf("minecraft:stone"));
    }

    @Test
    @DisplayName("acquires several material types in order")
    void multipleItems() {
        Harness h = new Harness();
        h.plan(
            new Requirement("minecraft:stone", "Stone", 16),
            new Requirement("minecraft:dirt", "Dirt", 32),
            new Requirement("minecraft:oak_planks", "Oak Planks", 64)
        );
        h.start();
        h.run(12, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(16, h.env.countOf("minecraft:stone"));
        assertEquals(32, h.env.countOf("minecraft:dirt"));
        assertEquals(64, h.env.countOf("minecraft:oak_planks"));
    }

    @Test
    @DisplayName("only the missing quantity is created when some items are already held")
    void acquiresOnlyMissingQuantity() {
        Harness h = new Harness();
        h.env.put(0, "minecraft:stone", 40);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.run(4, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(64, h.env.countOf("minecraft:stone"));
        assertEquals(24, h.actions.issued.get(0).amount());
    }

    @Test
    @DisplayName("a requirement already satisfied needs no give at all")
    void alreadySatisfied() {
        Harness h = new Harness();
        h.env.put(0, "minecraft:stone", 64);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.run(3, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertTrue(h.actions.issued.isEmpty());
    }

    @Test
    @DisplayName("quantities above one stack are split into stack-sized gives")
    void exceedsOneStack() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:stone", "Stone", 200));
        h.start();
        h.run(20, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(200, h.env.countOf("minecraft:stone"));
        assertTrue(h.actions.issued.size() >= 4, "200 items need at least four stack-sized gives");
        for (var give : h.actions.issued) {
            assertTrue(give.amount() <= 64, "no give may exceed the stack limit");
        }
    }

    // ---------------------------------------------------------------- failure handling

    @Test
    @DisplayName("survival mode stops acquisition with a fatal failure")
    void survivalMode() {
        Harness h = new Harness();
        h.env.creative = false;
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.tick(10);

        assertEquals(AcquisitionState.ERROR, h.state());
        assertTrue(h.fatalSeen);
        assertTrue(h.failures.contains(FailureType.NOT_CREATIVE));
    }

    @Test
    @DisplayName("a server-side rejection without permission is fatal")
    void noPermission() {
        Harness h = new Harness();
        h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.NO_PERMISSION);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.run(4, 10);

        assertEquals(AcquisitionState.ERROR, h.state());
        assertTrue(h.failures.contains(FailureType.NO_CREATIVE_PERMISSION));
    }

    @Test
    @DisplayName("an unknown item id is reported and stops the workflow")
    void unknownItem() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:nope", "Nope", 64));
        h.start();
        h.run(3, 10);

        assertEquals(AcquisitionState.ERROR, h.state());
        assertTrue(h.failures.contains(FailureType.UNKNOWN_ITEM));
    }

    @Test
    @DisplayName("a full inventory waits for space instead of failing")
    void fullInventoryWaits() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        h.env.fillAll("minecraft:cobblestone", 64);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.runUntil(java.util.Set.of(AcquisitionState.WAITING_FOR_SPACE), 8);

        assertEquals(AcquisitionState.WAITING_FOR_SPACE, h.state());
        assertTrue(h.actions.issued.isEmpty());
    }

    @Test
    @DisplayName("a ghost give is never counted as acquired")
    void ghostGiveIsNotCounted() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        for (int i = 0; i < 10; i++) h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.GHOST);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.run(21, 10);

        assertEquals(0, h.env.countOf("minecraft:stone"));
        assertFalse(h.state() == AcquisitionState.COMPLETED);
    }

    @Test
    @DisplayName("a temporary timeout is retried and can still succeed")
    void retriesAfterTimeout() {
        Harness h = new Harness();
        h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.TIMEOUT);
        h.plan(new Requirement("minecraft:stone", "Stone", 16));
        h.start();
        h.run(10, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(16, h.env.countOf("minecraft:stone"));
    }

    @Test
    @DisplayName("a persistent failure blocks the material instead of looping forever")
    void blocksAfterRetryLimit() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        for (int i = 0; i < 10; i++) h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.REJECT);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.runUntil(java.util.Set.of(AcquisitionState.WAITING_FOR_SPACE), 30);

        assertEquals(AcquisitionState.WAITING_FOR_SPACE, h.state());
        assertFalse(h.state() == AcquisitionState.COMPLETED);
    }

    // ---------------------------------------------------------------- control

    @Test
    @DisplayName("pause and resume keep the run alive")
    void pauseAndResume() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.tick(10);
        h.engine.pause();
        assertEquals(AcquisitionState.PAUSED, h.state());

        h.run(3, 10);
        assertEquals(AcquisitionState.PAUSED, h.state());

        h.engine.resume(h.now);
        h.run(6, 10);
        assertEquals(AcquisitionState.COMPLETED, h.state());
    }

    @Test
    @DisplayName("stop returns to idle and cancels any pending give")
    void stopCancels() {
        Harness h = new Harness();
        h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.PENDING);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.run(3, 10);
        assertEquals(AcquisitionState.VERIFYING, h.state());

        h.engine.stop();
        assertEquals(AcquisitionState.IDLE, h.state());
        assertTrue(h.actions.cancelCount > 0);
    }

    @Test
    @DisplayName("a world change resets the workflow without reporting completion")
    void worldChangeResets() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.tick(10);
        h.engine.onWorldChanged();

        assertEquals(AcquisitionState.IDLE, h.state());
        assertEquals(0, h.completions);
    }

    @Test
    @DisplayName("restart current and retry failed recover a blocked material")
    void restartAndRetry() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        for (int i = 0; i < 10; i++) h.actions.enqueue(AcquisitionFakes.FakeActions.Behaviour.REJECT);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.runUntil(java.util.Set.of(AcquisitionState.WAITING_FOR_SPACE), 30);
        assertEquals(AcquisitionState.WAITING_FOR_SPACE, h.state());

        h.actions.outcomes.clear();
        h.engine.retryFailed();
        h.runUntil(java.util.Set.of(AcquisitionState.COMPLETED), 10);
        assertEquals(AcquisitionState.COMPLETED, h.state());
    }

    // ---------------------------------------------------------------- batching

    @Test
    @DisplayName("a batch larger than the free space drains through the drop workflow")
    void batchesViaDrain() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, true, 0, 3, 0, 5000, 0));
        // Every slot is full, so the first stack of stone cannot be created without a drain.
        for (int i = 0; i < AcquisitionFakes.FakeEnv.SLOTS; i++) h.env.put(i, "minecraft:cobblestone", 64);

        h.plan(new Requirement("minecraft:stone", "Stone", 200));
        h.start();

        // Each time a drain starts, act like the drop engine: report the batch and free the space.
        int drains = 0;
        for (int i = 0; i < 200 && h.state() != AcquisitionState.COMPLETED; i++) {
            h.tick(10);
            if (h.drain.active && h.drain.draining) {
                drains++;
                h.drain.complete(Map.of());
                for (int s = 0; s < AcquisitionFakes.FakeEnv.SLOTS; s++) h.env.put(s, null, 0);
            }
        }

        assertTrue(drains >= 1, "expected at least one drain once the inventory had no free space");
        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(200, h.engine.snapshot().totalAcquired());
    }

    // ---------------------------------------------------------------- snapshot

    @Test
    @DisplayName("the snapshot distinguishes unavailable data from zero")
    void snapshotDistinguishesUnavailable() {
        Harness h = new Harness();
        AcquisitionSnapshot before = h.engine.snapshot();
        assertEquals(AcquisitionState.IDLE, before.state());
        assertEquals(0, before.materialsTotal());
        assertTrue(before.materials().isEmpty());

        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.tick(0);
        AcquisitionSnapshot during = h.engine.snapshot();
        assertEquals(1, during.materialsTotal());
        MaterialAcquisition stone = during.materials().get(0);
        assertEquals(64, stone.required());
        assertEquals(64, stone.needed());
        assertEquals(0, stone.held());
        assertFalse(stone.complete());
    }

    @Test
    @DisplayName("a plan with no materials never reports completion")
    void emptyPlan() {
        Harness h = new Harness();
        h.start();
        h.run(3, 10);
        assertEquals(AcquisitionState.IDLE, h.state());
        assertEquals(0, h.completions);
    }

    @Test
    @DisplayName("a stack-limited item (max 16) is acquired in 16-sized gives")
    void respectsStackLimit() {
        Harness h = new Harness();
        h.env.stackLimits.put("minecraft:ender_pearl", 16);
        h.plan(new Requirement("minecraft:ender_pearl", "Ender Pearl", 64));
        h.start();
        h.run(20, 10);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        for (var give : h.actions.issued) {
            assertTrue(give.amount() <= 16, "a 16-stack item must never be given in larger batches");
        }
    }

    @Test
    @DisplayName("the final batch is dropped before the run completes")
    void finalDrainDropsTheRemainder() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, true, 0, 3, 0, 5000, 0));
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();

        // Let the whole requirement be acquired; the last stack is still in the inventory.
        int drains = 0;
        for (int i = 0; i < 100 && h.state() != AcquisitionState.COMPLETED; i++) {
            h.tick(10);
            if (h.drain.active && h.drain.draining) {
                drains++;
                h.drain.complete(Map.of());
                for (int s = 0; s < AcquisitionFakes.FakeEnv.SLOTS; s++) h.env.put(s, null, 0);
            }
        }

        assertEquals(1, drains, "the held remainder must be dropped exactly once");
        assertEquals(AcquisitionState.COMPLETED, h.state());
    }

    @Test
    @DisplayName("with auto-drop off the run completes without dropping")
    void noFinalDrainWhenAutoDropOff() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, false, 0, 3, 0, 5000, 0));
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.runUntil(java.util.Set.of(AcquisitionState.COMPLETED), 20);

        assertEquals(AcquisitionState.COMPLETED, h.state());
        assertEquals(0, h.drain.beginCount);
    }

    @Test
    @DisplayName("a drain that cannot start waits for space instead of looping")
    void drainUnavailableWaits() {
        Harness h = new Harness();
        h.engine.setConfig(new AcquisitionConfig(true, true, 0, 3, 0, 5000, 0));
        h.drain.canBegin = false;
        for (int i = 0; i < AcquisitionFakes.FakeEnv.SLOTS; i++) h.env.put(i, "minecraft:cobblestone", 64);
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.runUntil(java.util.Set.of(AcquisitionState.WAITING_FOR_SPACE), 10);

        assertEquals(AcquisitionState.WAITING_FOR_SPACE, h.state());
        assertEquals(0, h.drain.beginCount);
    }

    @Test
    @DisplayName("the snapshot is populated while a run is in progress")
    void snapshotDuringRun() {
        Harness h = new Harness();
        h.plan(new Requirement("minecraft:stone", "Stone", 64));
        h.start();
        h.tick(10);
        assertNotNull(h.engine.snapshot());
        assertTrue(h.engine.snapshot().percent() >= 0);
    }
}
