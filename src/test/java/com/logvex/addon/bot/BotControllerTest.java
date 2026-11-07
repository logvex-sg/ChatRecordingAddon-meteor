package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotController;
import com.logvex.addon.bot.core.BotListener;
import com.logvex.addon.bot.core.BotState;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskState;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.exec.StandardExecutors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end controller behaviour with fakes: an objective is planned, executed, and completed;
 * safety stops pause the bot; a disconnect resets in-progress work; and disabling stops everything.
 */
class BotControllerTest {
    /** Records listener callbacks so a test can assert on the reported lifecycle. */
    private static final class RecordingListener implements BotListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onStateChanged(BotState state, String reason) {
            events.add("state:" + state);
        }

        @Override
        public void onObjectiveCompleted(boolean success, String summary) {
            events.add("objective:" + success);
        }

        @Override
        public void onFailure(String message) {
            events.add("failure");
        }
    }

    private BotController controller(BotFakes.Harness h, BotListener listener) {
        BotController controller = new BotController(StandardExecutors.create(), listener, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        return controller;
    }

    @Test
    void plansAndCompletesAGatherObjective() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        h.nav.arrivedNow = true;

        RecordingListener listener = new RecordingListener();
        BotController controller = controller(h, listener);
        controller.addGoal("gather 1 oak_log", "gather", "minecraft:oak_log", 1);

        // Run enough ticks for planning, mining and verification.
        for (int i = 0; i < 10 && !controller.state().isTerminal(); i++) {
            if (controller.activeTask() != null && controller.activeTask().required() == 1) {
                h.inventory.items.put("minecraft:oak_log", 1);
            }
            h.clock.advance(200);
            controller.tick();
        }

        assertEquals(BotState.COMPLETED, controller.state());
        assertTrue(listener.events.contains("objective:true"));
    }

    @Test
    void lowHealthPausesTheBot() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.health = 3;
        BotController controller = controller(h, new RecordingListener());
        controller.start();
        controller.tick();
        assertEquals(BotState.PAUSED, controller.state());
    }

    @Test
    void aDisconnectPausesAndClearsTheActiveTask() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        BotController controller = controller(h, new RecordingListener());
        controller.addGoal("gather 8 oak_log", "gather", "minecraft:oak_log", 8);
        for (int i = 0; i < 5; i++) {
            h.clock.advance(200);
            controller.tick();
        }
        assertNotNull(controller.activeTask());

        controller.onWorldChanged();
        assertTrue(controller.activeTask() == null);
        assertEquals(BotState.PAUSED, controller.state());
    }

    @Test
    void disablingStopsEverything() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        BotController controller = controller(h, new RecordingListener());
        controller.addGoal("gather 4 oak_log", "gather", "minecraft:oak_log", 4);
        controller.tick();

        controller.disable();
        assertEquals(BotState.DISABLED, controller.state());
        assertTrue(controller.activeTask() == null);
    }

    @Test
    void aTaskThatNeverMakesProgressTimesOutAndFails() {
        BotFakes.Harness h = new BotFakes.Harness();
        // No blocks anywhere, so the gather task keeps asking for a retry and never progresses.
        BotController controller = controller(h, new RecordingListener());
        controller.addGoal("gather 4 oak_log", "gather", "minecraft:oak_log", 4);

        for (int i = 0; i < 800 && controller.state() != BotState.FAILED; i++) {
            h.clock.advance(1000);
            controller.tick();
        }
        assertEquals(BotState.FAILED, controller.state());
    }

    @Test
    void aHardFailureIsRetriedThenAborts() {
        BotFakes.Harness h = new BotFakes.Harness();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new com.logvex.addon.bot.core.ExecutorRegistry();
        registry.register(new com.logvex.addon.bot.core.TaskExecutor() {
            @Override
            public com.logvex.addon.bot.core.TaskType type() {
                return TaskType.GATHERING;
            }

            @Override
            public TaskOutcome step(BotTask task, com.logvex.addon.bot.core.TaskContext context) {
                calls.incrementAndGet();
                return TaskOutcome.FAILED;
            }
        });
        BotController controller = new BotController(registry, new RecordingListener(), h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        controller.addGoal("gather 1 oak_log", "gather", "minecraft:oak_log", 1);

        for (int i = 0; i < 60 && controller.state() != BotState.FAILED; i++) {
            h.clock.advance(1000);
            controller.tick();
        }
        assertEquals(BotState.FAILED, controller.state());
        // The retry limit is bounded: the step must not be called an unbounded number of times.
        assertTrue(calls.get() <= 8, "step was called " + calls.get() + " times");
    }

    @Test
    void anUnplannableObjectiveIsReportedNotQueued() {
        BotFakes.Harness h = new BotFakes.Harness();
        RecordingListener listener = new RecordingListener();
        BotController controller = controller(h, listener);
        controller.addGoal("dance", "unknownverb", "", 0);
        for (int i = 0; i < 4; i++) {
            h.clock.advance(200);
            controller.tick();
        }
        assertTrue(listener.events.contains("objective:false"));
    }

    @Test
    void startPauseResumeRoundTrips() {
        BotFakes.Harness h = new BotFakes.Harness();
        BotController controller = controller(h, new RecordingListener());
        assertTrue(controller.start());
        h.clock.advance(100);
        controller.tick();
        assertTrue(controller.pause("user"));
        assertEquals(BotState.PAUSED, controller.state());
        assertTrue(controller.resume());
        h.clock.advance(100);
        controller.tick();
        assertTrue(controller.state().isActive() || controller.state() == BotState.COMPLETED
            || controller.state() == BotState.WAITING);
    }

    @Test
    void queueIsBoundedAndReportsOverflow() {
        BotFakes.Harness h = new BotFakes.Harness();
        BotController controller = controller(h, new RecordingListener());
        for (int i = 0; i < 600; i++) {
            controller.addTask(new BotTask(TaskType.GATHERING, "t" + i).itemId("minecraft:stone").required(1));
        }
        assertTrue(controller.queue().size() <= 512);
    }

    @Test
    void aTaskIsOnlyMarkedDoneOnceItsEffectIsObserved() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        h.nav.arrivedNow = true;
        BotController controller = controller(h, new RecordingListener());
        controller.addGoal("gather 1 oak_log", "gather", "minecraft:oak_log", 1);

        h.clock.advance(200);
        controller.tick();
        h.clock.advance(200);
        controller.tick();

        // The executor mined but the inventory is empty: the task must not be recorded as done.
        for (var entry : controller.history().entries()) {
            assertFalse(entry.outcome() == TaskState.DONE && entry.description().contains("oak"));
        }
    }

    @Test
    void emergencyStopCancelsEverythingAndReturnsToIdle() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        BotController controller = controller(h, new RecordingListener());
        controller.start();
        controller.addTask(new BotTask(TaskType.GATHERING, "a").itemId("minecraft:oak_log").required(5));
        controller.addTask(new BotTask(TaskType.GATHERING, "b").itemId("minecraft:oak_log").required(5));

        h.clock.advance(200);
        controller.tick();
        assertTrue(controller.queue().size() > 0);

        controller.emergencyStop();

        assertEquals(BotState.IDLE, controller.state());
        assertTrue(controller.queue().isEmpty(), "emergency stop must empty the queue");
        assertTrue(h.nav.ticks >= 0);
    }

    @Test
    void emergencyStopFromIdleIsSafe() {
        BotFakes.Harness h = new BotFakes.Harness();
        BotController controller = controller(h, new RecordingListener());
        controller.emergencyStop();
        assertEquals(BotState.IDLE, controller.state());
        assertTrue(controller.queue().isEmpty());
    }
}
