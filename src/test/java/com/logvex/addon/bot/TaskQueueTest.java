package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskPriority;
import com.logvex.addon.bot.core.TaskQueue;
import com.logvex.addon.bot.core.TaskState;
import com.logvex.addon.bot.core.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue must respect order, dependencies and its hard cap. */
class TaskQueueTest {
    private static BotTask task(TaskType type, String item) {
        return new BotTask(type, "task " + item).itemId(item).required(1);
    }

    @Test
    void insertionOrderIsPreserved() {
        TaskQueue queue = new TaskQueue();
        BotTask a = task(TaskType.GATHERING, "a");
        BotTask b = task(TaskType.GATHERING, "b");
        BotTask c = task(TaskType.GATHERING, "c");
        queue.add(a);
        queue.add(b);
        queue.add(c);

        assertEquals(a.id(), queue.nextRunnable().orElseThrow().id());
        queue.markRunning(a);
        queue.markDone(a);
        assertEquals(b.id(), queue.nextRunnable().orElseThrow().id());
    }

    @Test
    void aDependentTaskIsNotRunnableUntilItsDependencyFinishes() {
        TaskQueue queue = new TaskQueue();
        BotTask gather = task(TaskType.GATHERING, "oak_log");
        BotTask craft = task(TaskType.CRAFTING, "planks").dependOn(gather.id());
        queue.add(gather);
        queue.add(craft);
        queue.refreshStates();

        assertEquals(gather.id(), queue.nextRunnable().orElseThrow().id());
        queue.markRunning(gather);
        queue.markDone(gather);
        queue.refreshStates();

        assertEquals(craft.id(), queue.nextRunnable().orElseThrow().id());
    }

    @Test
    void anEmptyQueueHasNoRunnableTask() {
        TaskQueue queue = new TaskQueue();
        assertTrue(queue.nextRunnable().isEmpty());
        assertTrue(queue.isEmpty());
    }

    @Test
    void theQueueIsBounded() {
        TaskQueue queue = new TaskQueue();
        for (int i = 0; i < TaskQueue.MAX_TASKS; i++) {
            assertTrue(queue.add(task(TaskType.GATHERING, "i" + i)));
        }
        assertFalse(queue.add(task(TaskType.GATHERING, "overflow")));
        assertEquals(TaskQueue.MAX_TASKS, queue.size());
    }

    @Test
    void removingATaskByIdWorks() {
        TaskQueue queue = new TaskQueue();
        BotTask a = task(TaskType.GATHERING, "a");
        queue.add(a);
        assertTrue(queue.remove(a.id()));
        assertTrue(queue.isEmpty());
    }

    @Test
    void finishedTasksArePruned() {
        TaskQueue queue = new TaskQueue();
        BotTask a = task(TaskType.GATHERING, "a");
        queue.add(a);
        queue.markRunning(a);
        queue.markDone(a);
        queue.pruneFinished();
        assertTrue(queue.isEmpty());
    }

    @Test
    void priorityCanBeChanged() {
        TaskQueue queue = new TaskQueue();
        BotTask a = task(TaskType.GATHERING, "a");
        queue.add(a);
        assertTrue(queue.setPriority(a.id(), TaskPriority.CRITICAL));
        assertEquals(TaskPriority.CRITICAL, queue.get(a.id()).orElseThrow().priority());
    }

    @Test
    void cancellingAllMarksEveryPendingTaskCancelled() {
        TaskQueue queue = new TaskQueue();
        BotTask a = task(TaskType.GATHERING, "a");
        BotTask b = task(TaskType.GATHERING, "b");
        queue.add(a);
        queue.add(b);
        queue.cancelAll();
        assertEquals(TaskState.CANCELLED, a.state());
        assertEquals(TaskState.CANCELLED, b.state());
    }

    @Test
    void aSelfDependencyIsIgnored() {
        BotTask a = task(TaskType.GATHERING, "a");
        a.dependOn(a.id());
        assertTrue(a.dependsOn().isEmpty());
        Optional<BotTask> ignored = Optional.empty();
        assertTrue(ignored.isEmpty());
    }
}
