package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.DropAction;
import com.logvex.addon.schematicdropper.core.DropConfig;
import com.logvex.addon.schematicdropper.core.DropEngine;
import com.logvex.addon.schematicdropper.core.DropPlan;
import com.logvex.addon.schematicdropper.core.EngineListener;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.schematicdropper.core.RecoveryMode;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMode;
import com.logvex.addon.schematicdropper.core.StackEntry;
import com.logvex.addon.schematicdropper.core.StackStrategy;
import com.logvex.addon.schematicdropper.core.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real state machine with fake ports. The fake inventory is mutated by the fake action
 * port only when a drop is confirmed, which mirrors how a real server behaves.
 */
class DropEngineTest {

    /** Wires an engine to fakes and applies confirmed transfers to the inventory. */
    private static final class Harness {
        final Fakes.FakeEnv env = new Fakes.FakeEnv();
        final Fakes.FakeRotation rotation = new Fakes.FakeRotation();
        final Fakes.FakeActions actions = new Fakes.FakeActions();
        final ProgressTracker tracker = new ProgressTracker();
        final DropEngine engine;
        final List<State> states = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        final List<FailureType> failures = new ArrayList<>();
        int completions;

        long now = 1_000_000L;

        Harness(DropConfig config) {
            engine = new DropEngine(env, rotation, actions, tracker, new EngineListener() {
                @Override
                public void onStateChanged(State state) {
                    states.add(state);
                }

                @Override
                public void onConfirmed(String itemId, int amount, int remaining) {
                    // Mirror the server: a confirmed transfer really removes items.
                    env.consume(itemId, amount);
                }

                @Override
                public void onFailure(FailureType type, String detail) {
                    failures.add(type);
                }

                @Override
                public void onCompleted(long totalDropped, long elapsedMs) {
                    completions++;
                }

                @Override
                public void onFeedback(String message) {
                    feedback.add(message);
                }
            });
            engine.setConfig(config);
        }

        void plan(Requirement... requirements) {
            MaterialPlan plan = new MaterialPlan();
            plan.set(List.of(requirements));
            tracker.setPlan(plan);
        }

        void start() {
            engine.start(now);
        }

        /** Runs one tick, advancing the clock by {@code deltaMs}. */
        void tick(long deltaMs) {
            now += deltaMs;
            engine.tick(now);
        }

        void run(int ticks, long deltaMs) {
            for (int i = 0; i < ticks; i++) tick(deltaMs);
        }

        State state() {
            return engine.state();
        }
    }

    private static DropConfig config(int retryLimit, RecoveryMode recovery) {
        DropConfig d = DropConfig.defaults();
        return new DropConfig(
            d.autoContinue(), 0, retryLimit, 0, 50, d.pauseOnMissing(),
            false, RotationMode.INSTANT, d.rotationSpeed(), d.restoreRotation(),
            d.targetMode(), d.targetUpdateMs(), d.orderingMode(), recovery,
            d.inventoryMode(), DropAction.DROP_ONE, d.stackStrategy(), d.syncTimeoutMs()
        );
    }

    private static DropConfig fast() {
        return config(3, RecoveryMode.STRICT_ORDER);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("drops a single material completely and completes")
    void singleMaterial() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 5));
        h.env.set("minecraft:cobblestone", 10);
        h.start();

        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(5, h.tracker.totalConfirmed());
        assertEquals(1, h.completions);
        assertEquals(5, h.actions.issued.size());
    }

    @Test
    @DisplayName("never drops more than the required quantity from a larger stack")
    void neverDropsMoreThanRequired() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 3));
        h.env.set("minecraft:cobblestone", 64);
        h.start();

        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(3, h.tracker.totalConfirmed());
        assertEquals(3, h.actions.issued.size());
        // 61 items must remain in the inventory.
        assertEquals(61, h.env.countOf("minecraft:cobblestone"));
    }

    @Test
    @DisplayName("processes multiple materials and continues between them")
    void multipleMaterials() {
        Harness h = new Harness(fast());
        h.plan(
            new Requirement("minecraft:cobblestone", "Cobblestone", 2),
            new Requirement("minecraft:stone", "Stone", 3)
        );
        h.env.set("minecraft:cobblestone", 8);
        h.env.set("minecraft:stone", 8);
        h.start();

        h.run(60, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(5, h.tracker.totalConfirmed());
        assertEquals(2, h.tracker.confirmed("minecraft:cobblestone"));
        assertEquals(3, h.tracker.confirmed("minecraft:stone"));
    }

    @Test
    @DisplayName("uses multiple stacks of the same material")
    void multipleStacks() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 5));
        h.env.setStacks("minecraft:cobblestone",
            new StackEntry(0, 2),
            new StackEntry(9, 10));
        h.engine.setStackStrategy(StackStrategy.SMALLEST_FIRST);
        h.start();

        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(5, h.tracker.totalConfirmed());
        // The small stack (2) is drained first, then the larger one.
        assertTrue(h.actions.issued.stream().anyMatch(p -> p.slot() == 0), "small stack used");
        assertTrue(h.actions.issued.stream().anyMatch(p -> p.slot() == 9), "large stack used");
    }

    @Test
    @DisplayName("drop-stack action removes the whole stack and is used when it fits")
    void dropStackAction() {
        DropConfig d = config(3, RecoveryMode.STRICT_ORDER);
        DropConfig stack = new DropConfig(
            d.autoContinue(), 0, 3, 0, 50, d.pauseOnMissing(), false, d.rotationMode(),
            d.rotationSpeed(), d.restoreRotation(), d.targetMode(), d.targetUpdateMs(),
            d.orderingMode(), d.recoveryMode(), d.inventoryMode(), DropAction.DROP_STACK,
            d.stackStrategy(), d.syncTimeoutMs());

        Harness h = new Harness(stack);
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 10));
        h.env.setStacks("minecraft:cobblestone", new StackEntry(0, 10));
        h.actions.enqueue(Fakes.FakeActions.Behaviour.CONFIRM, 10);
        h.start();

        h.run(20, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(10, h.tracker.totalConfirmed());
        assertEquals(1, h.actions.issued.size(), "one whole-stack action");
        assertEquals(DropAction.DROP_STACK, h.actions.issued.get(0).action());
    }

    @Test
    @DisplayName("a partial stack is topped up with a following stack")
    void partialStacksCombine() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        // 2 + 2 in two stacks.
        h.env.setStacks("minecraft:cobblestone",
            new StackEntry(1, 2),
            new StackEntry(2, 2));
        h.start();

        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(4, h.tracker.totalConfirmed());
    }

    @Test
    @DisplayName("progress does not advance while a confirmation is pending")
    void noProgressBeforeConfirmation() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        h.env.set("minecraft:cobblestone", 8);
        h.actions.enqueue(Fakes.FakeActions.Behaviour.PENDING);
        h.start();

        // Drive into WAITING_FOR_CONFIRMATION and hold there.
        h.run(6, 10);

        assertEquals(State.WAITING_FOR_CONFIRMATION, h.state());
        assertEquals(0, h.tracker.totalConfirmed(), "nothing confirmed yet");
        assertEquals(0.0, h.tracker.percent(), 0.0001);
    }

    @Test
    @DisplayName("a pending confirmation that never resolves times out and is retried")
    void timeoutIsRetried() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 1));
        h.env.set("minecraft:cobblestone", 5);
        h.actions.enqueue(Fakes.FakeActions.Behaviour.PENDING);
        h.start();

        h.run(200, 10); // past the 1500 ms sync timeout, then retried and confirmed

        assertTrue(h.failures.contains(FailureType.SERVER_TIMEOUT), "timeout recorded");
        assertEquals(1, h.tracker.totalConfirmed());
        assertEquals(State.COMPLETED, h.state());
    }

    @Test
    @DisplayName("rejected operations are retried up to the limit and never count as progress")
    void rejectionRetries() {
        Harness h = new Harness(config(2, RecoveryMode.STRICT_ORDER));
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 1));
        h.env.set("minecraft:cobblestone", 5);
        // Every attempt is rejected, so the run can never make progress.
        for (int i = 0; i < 500; i++) h.actions.enqueue(Fakes.FakeActions.Behaviour.REJECT);
        h.start();

        h.run(120, 10);

        assertEquals(0, h.tracker.totalConfirmed(), "rejected drops never count");
        assertTrue(h.failures.contains(FailureType.SERVER_REJECTION));
        assertTrue(h.tracker.failedOperations() > 0, "failures were recorded");
        // The engine waits for the material instead of silently claiming completion.
        assertFalse(h.state() == State.COMPLETED);
    }

    @Test
    @DisplayName("a missing material pauses instead of falsely completing")
    void missingMaterialDoesNotComplete() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        // Nothing in the inventory.
        h.start();

        h.run(40, 10);

        assertEquals(State.WAITING_FOR_INVENTORY, h.state());
        assertFalse(h.state() == State.COMPLETED);
        assertEquals(0, h.completions);
        assertEquals(1, h.tracker.snapshot().missingTypes());
        assertEquals(4, h.tracker.missingQuantity("minecraft:cobblestone"));
    }

    @Test
    @DisplayName("automatically resumes once the missing material appears")
    void resumesWhenMaterialAppears() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 2));
        h.start();

        h.run(10, 10);
        assertEquals(State.WAITING_FOR_INVENTORY, h.state());

        h.env.set("minecraft:cobblestone", 4);
        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(2, h.tracker.totalConfirmed());
    }

    @Test
    @DisplayName("pause stops work and resume continues from the confirmed point")
    void pauseAndResume() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        h.env.set("minecraft:cobblestone", 8);
        h.start();

        // Run until at least one drop is confirmed.
        while (h.tracker.totalConfirmed() == 0 && h.now < 1_010_000L) h.tick(10);
        assertTrue(h.tracker.totalConfirmed() >= 1, "at least one confirmed drop");
        h.tick(200); // past the operation delay so the next drop would otherwise start

        int confirmedBefore = (int) h.tracker.totalConfirmed();
        h.engine.pause();
        assertEquals(State.PAUSED, h.state());

        h.run(20, 10);
        assertEquals(confirmedBefore, (int) h.tracker.totalConfirmed(), "no work while paused");

        h.engine.resume(h.now);
        h.run(40, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(4, h.tracker.totalConfirmed());
    }

    @Test
    @DisplayName("pause during an in-flight drop is honoured once it confirms")
    void pauseDuringDropIsDeferred() {
        Harness h = new Harness(fast());
        h.actions.useControllable = true;
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        h.env.set("minecraft:cobblestone", 8);
        h.start();

        // Drive until a drop has been issued and the engine is waiting for the server.
        while (h.state() != State.WAITING_FOR_CONFIRMATION && h.now < 1_010_000L) h.tick(10);
        assertEquals(State.WAITING_FOR_CONFIRMATION, h.state());

        h.engine.pause();
        assertEquals(State.WAITING_FOR_CONFIRMATION, h.state(), "an in-flight drop is not abandoned");

        // The server confirms the drop; the remembered pause must then apply.
        h.actions.controllable.confirm(1);
        // The pause is applied at the top of the next tick, before any new work starts.
        h.tick(10);
        h.tick(10);

        assertEquals(State.PAUSED, h.state(), "the deferred pause was applied");
        assertEquals(1, h.tracker.totalConfirmed(), "the confirmed drop still counted");

        int issued = h.actions.issued.size();
        h.run(20, 10);
        assertEquals(State.PAUSED, h.state());
        assertEquals(issued, h.actions.issued.size(), "no new drops while paused");

        h.engine.resume(h.now);
        h.run(60, 10);
        assertEquals(State.COMPLETED, h.state());
        assertEquals(4, h.tracker.totalConfirmed());
    }

    @Test
    @DisplayName("stopping safely cancels the pending transaction")
    void stopCancelsPending() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 4));
        h.env.set("minecraft:cobblestone", 8);
        h.start();
        h.run(8, 10);

        h.engine.stop();

        assertEquals(State.IDLE, h.state());
        assertTrue(h.actions.cancelCount >= 1, "cancelPending was called");
    }

    @Test
    @DisplayName("reset progress returns to idle without duplicating dropped items")
    void resetProgress() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 2));
        h.env.set("minecraft:cobblestone", 5);
        h.start();
        h.run(20, 10);
        assertEquals(2, h.tracker.totalConfirmed());

        h.engine.resetProgress();

        assertEquals(State.IDLE, h.state());
        assertEquals(0, h.tracker.totalConfirmed());
        assertEquals(3, h.env.countOf("minecraft:cobblestone"), "inventory untouched by reset");
    }

    @Test
    @DisplayName("an empty plan completes immediately without dropping")
    void emptyPlan() {
        Harness h = new Harness(fast());
        h.start();
        h.run(10, 10);

        assertEquals(0, h.actions.issued.size());
        assertEquals(State.IDLE, h.state(), "no plan -> back to idle");
    }

    @Test
    @DisplayName("losing the world pauses the run instead of dropping blindly")
    void worldChangeIsSafe() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 2));
        h.env.set("minecraft:cobblestone", 5);
        h.start();
        h.run(6, 10);

        h.env.canRun = false;
        h.run(5, 10);
        assertEquals(State.WAITING_FOR_INVENTORY, h.state());

        h.env.canRun = true;
        h.run(40, 10);
        assertEquals(State.COMPLETED, h.state());
    }

    @Test
    @DisplayName("rotation happens before the drop and converges to the target")
    void rotationBeforeDrop() {
        DropConfig d = DropConfig.defaults();
        DropConfig rotating = new DropConfig(
            true, 0, 3, 0, 50, true,
            true, RotationMode.INSTANT, 30f, true,
            d.targetMode(), 0, d.orderingMode(), d.recoveryMode(),
            d.inventoryMode(), DropAction.DROP_ONE, d.stackStrategy(), d.syncTimeoutMs());

        Harness h = new Harness(rotating);
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 1));
        h.env.set("minecraft:cobblestone", 5);
        h.env.current = new Rotation(10f, 5f);
        h.env.desired = new Rotation(90f, 20f);
        h.start();

        h.run(20, 10);

        assertTrue(h.states.contains(State.ROTATING), "rotated before dropping");
        assertNotNull(h.rotation.lastApplied);
        assertEquals(90f, h.rotation.lastApplied.yaw(), 0.01f);
        assertEquals(20f, h.rotation.lastApplied.pitch(), 0.01f);
        assertEquals(State.COMPLETED, h.state());
    }

    @Test
    @DisplayName("the original rotation is restored after completion")
    void rotationRestored() {
        DropConfig d = DropConfig.defaults();
        DropConfig rotating = new DropConfig(
            true, 0, 3, 0, 50, true,
            true, RotationMode.INSTANT, 30f, true,
            d.targetMode(), 0, d.orderingMode(), d.recoveryMode(),
            d.inventoryMode(), DropAction.DROP_ONE, d.stackStrategy(), d.syncTimeoutMs());

        Harness h = new Harness(rotating);
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 1));
        h.env.set("minecraft:cobblestone", 5);
        h.env.current = new Rotation(42f, -7f);
        h.env.desired = new Rotation(120f, 30f);
        h.start();

        h.run(20, 10);

        assertNotNull(h.rotation.restored, "restored the pre-run rotation");
        assertEquals(42f, h.rotation.restored.yaw(), 0.01f);
        assertEquals(-7f, h.rotation.restored.pitch(), 0.01f);
    }

    @Test
    @DisplayName("an invalid rotation target is reported and not applied")
    void invalidTargetIsSafe() {
        DropConfig d = DropConfig.defaults();
        DropConfig rotating = new DropConfig(
            true, 0, 1, 0, 50, true,
            true, RotationMode.INSTANT, 30f, true,
            d.targetMode(), 0, d.orderingMode(), d.recoveryMode(),
            d.inventoryMode(), DropAction.DROP_ONE, d.stackStrategy(), d.syncTimeoutMs());

        Harness h = new Harness(rotating);
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 1));
        h.env.set("minecraft:cobblestone", 5);
        h.env.desired = null; // no valid target
        h.start();

        h.run(10, 10);

        assertTrue(h.failures.contains(FailureType.TARGET_INVALID));
        assertEquals(0, h.rotation.applyCount, "never rotated toward an invalid target");
        assertEquals(0, h.tracker.totalConfirmed());
    }

    @Test
    @DisplayName("best-effort recovery blocks an unavailable material and finishes the rest")
    void bestEffortSkipsUnavailable() {
        Harness h = new Harness(config(1, RecoveryMode.BEST_EFFORT));
        h.plan(
            new Requirement("minecraft:diamond", "Diamond", 1),
            new Requirement("minecraft:cobblestone", "Cobblestone", 1)
        );
        // Diamond is missing; cobblestone is available.
        h.env.set("minecraft:cobblestone", 3);
        h.start();

        h.run(80, 10);

        assertEquals(1, h.tracker.confirmed("minecraft:cobblestone"));
        assertEquals(0, h.tracker.confirmed("minecraft:diamond"));
        // The run must not claim completion while a material is still missing.
        assertFalse(h.state() == State.COMPLETED);
        assertEquals(State.WAITING_FOR_INVENTORY, h.state());
    }

    @Test
    @DisplayName("only the needed amount is taken when a stack is larger than the remainder")
    void partialRequirementFromFullStack() {
        Harness h = new Harness(fast());
        h.plan(new Requirement("minecraft:cobblestone", "Cobblestone", 2));
        h.env.setStacks("minecraft:cobblestone", new StackEntry(0, 64));
        h.start();

        h.run(30, 10);

        assertEquals(State.COMPLETED, h.state());
        assertEquals(2, h.tracker.totalConfirmed());
        assertEquals(62, h.env.countOf("minecraft:cobblestone"));
        for (DropPlan plan : h.actions.issued) {
            assertEquals(1, plan.expectedOut(), "each action drops exactly one");
        }
    }
}
