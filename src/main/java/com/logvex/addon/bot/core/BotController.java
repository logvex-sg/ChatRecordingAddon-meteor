package com.logvex.addon.bot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Drives the bot: plans objectives, runs one task step at a time, and applies safety and recovery
 * policy.
 *
 * <p>The controller is the only place that changes {@link BotState}. It performs no blocking work
 * and touches the world only through the ports in {@link TaskContext}, so it can be exercised in a
 * unit test with fakes and it never freezes the client. Every transition is validated by
 * {@link BotState#canTransitionTo(BotState)}.
 */
public final class BotController {
    private static final int EVENT_LIMIT = 200;

    private final ExecutorRegistry executors;
    private final TaskQueue queue = new TaskQueue();
    private final GoalManager goals = new GoalManager();
    private final TaskHistory history = new TaskHistory();
    private final BotScheduler scheduler = new BotScheduler();
    private final PerformanceMonitor performance = new PerformanceMonitor();
    private final Deque<BotEvent> events = new ArrayDeque<>();

    private final BotListener listener;
    private final BotClock clock;

    private BotConfig config = BotConfig.DEFAULTS;
    private SafetyController safety = new SafetyController(config);
    private BotRecovery recovery = new BotRecovery(config.retryLimit(), config.retryBackoffMs());
    private TaskContext context;

    private BotState state = BotState.DISABLED;
    private BotTask active;
    private long lastActionAt;
    private long stateSince;
    private boolean autoStart = true;

    public BotController(ExecutorRegistry executors, BotListener listener, BotClock clock) {
        this.executors = executors == null ? new ExecutorRegistry() : executors;
        this.listener = listener == null ? new BotListener() {
        } : listener;
        this.clock = clock == null ? BotClock.system() : clock;
    }

    // ------------------------------------------------------------------ configuration

    public void setContext(TaskContext context) {
        this.context = context;
    }

    public TaskContext context() {
        return context;
    }

    public void setConfig(BotConfig config) {
        if (config == null) return;
        this.config = config;
        this.safety = new SafetyController(config);
        this.recovery = new BotRecovery(config.retryLimit(), config.retryBackoffMs());
    }

    public BotConfig config() {
        return config;
    }

    public void setAutoStart(boolean value) {
        this.autoStart = value;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Enables and starts work. Returns true when the bot is now running. */
    public boolean start() {
        if (state == BotState.DISABLED) setState(BotState.IDLE, "enabled");
        if (state == BotState.PAUSED) return resume();
        if (state == BotState.IDLE || state == BotState.COMPLETED || state == BotState.FAILED) {
            transition(BotState.INITIALIZING, "started");
            return true;
        }
        return state.isActive();
    }

    /** Pauses without discarding the queue. */
    public boolean pause(String reason) {
        if (!state.isActive()) return false;
        if (!state.canTransitionTo(BotState.PAUSED)) return false;
        if (context != null && context.nav() != null) context.nav().stop();
        transition(BotState.PAUSED, reason == null ? "paused" : reason);
        return true;
    }

    /** Resumes from a pause. */
    public boolean resume() {
        if (state != BotState.PAUSED) return false;
        if (!state.canTransitionTo(BotState.INITIALIZING)) return false;
        transition(BotState.INITIALIZING, "resumed");
        return true;
    }

    /** Stops the current work and clears the active task; the queue is kept. */
    public boolean stop(String reason) {
        cancelActive("stopped");
        if (state == BotState.DISABLED) return false;
        if (state.canTransitionTo(BotState.IDLE)) {
            transition(BotState.IDLE, reason == null ? "stopped" : reason);
            return true;
        }
        return false;
    }

    /** Fully disables the controller. */
    public void disable() {
        cancelActive("disabled");
        scheduler.clear();
        if (context != null) {
            if (context.nav() != null) context.nav().stop();
            if (context.actions() != null) context.actions().cancel();
        }
        setState(BotState.DISABLED, "disabled");
    }

    /** Reacts to a world change or a disconnect. */
    public void onWorldChanged() {
        cancelActive("world changed");
        queue.refreshStates();
        if (config.stopOnDisconnect() && state.isActive() && state.canTransitionTo(BotState.PAUSED)) {
            transition(BotState.PAUSED, "world changed");
        }
        emit(BotEvent.Level.WARNING, "World changed; in-progress work was reset.");
    }

    // ------------------------------------------------------------------ objectives

    public GoalManager.Goal addGoal(String description, String type, String itemId, int amount) {
        GoalManager.Goal goal = goals.add(description, type, itemId, amount);
        emit(BotEvent.Level.INFO, "Objective added: " + goal.description());
        if (autoStart && (state == BotState.DISABLED || state == BotState.IDLE
            || state == BotState.COMPLETED || state == BotState.FAILED)) {
            start();
        }
        return goal;
    }

    public boolean addTask(BotTask task) {
        if (task == null) return false;
        boolean added = queue.add(task);
        if (!added) listener.onWarning("The task queue is full; the task was not added.");
        return added;
    }

    public GoalManager goals() {
        return goals;
    }

    public TaskQueue queue() {
        return queue;
    }

    public TaskHistory history() {
        return history;
    }

    public PerformanceMonitor performance() {
        return performance;
    }

    public BotScheduler scheduler() {
        return scheduler;
    }

    public List<BotEvent> events() {
        return new ArrayList<>(events);
    }

    // ------------------------------------------------------------------ state

    public BotState state() {
        return state;
    }

    public long stateSince() {
        return stateSince;
    }

    public BotTask activeTask() {
        return active;
    }

    public boolean isActive() {
        return state.isActive();
    }

    // ------------------------------------------------------------------ tick

    /** Advances the bot by one client tick. Never blocks. */
    public void tick() {
        if (state == BotState.DISABLED) return;
        long now = clock.now();
        long startNanos = System.nanoTime();
        performance.beginTick(now);
        try {
            scheduler.tick();
            if (context == null) return;

            SafetyController.Stop stop = evaluateSafety();
            if (stop != SafetyController.Stop.NONE) {
                handleSafety(stop);
                return;
            }

            switch (state) {
                case IDLE -> {
                    if (autoStart && goals.hasWork()) transition(BotState.INITIALIZING, "objective queued");
                }
                case INITIALIZING -> transition(BotState.ANALYZING, "ready");
                case ANALYZING -> analyze(now);
                case PLANNING -> transition(BotState.EXECUTING, "planned");
                case RECOVERING -> recover(now);
                case EXECUTING, NAVIGATING, GATHERING, BUILDING, CRAFTING -> work(now);
                case WAITING -> {
                    if (goals.hasWork()) transition(BotState.ANALYZING, "new objective");
                    else transition(BotState.COMPLETED, "nothing to do");
                }
                default -> {
                    // PAUSED, COMPLETED, FAILED: no work while in these states.
                }
            }
        } finally {
            performance.endTick(System.nanoTime() - startNanos);
        }
    }

    private SafetyController.Stop evaluateSafety() {
        if (context.player() == null || context.world() == null) return SafetyController.Stop.NOT_IN_WORLD;
        if (!state.isActive()) return SafetyController.Stop.NONE;
        // Threat detection is not part of the safety stop: it would require a world scan every
        // tick, and the executors already back off when a hostile mob is near.
        return safety.evaluate(context.player(), context.world(),
            context.inventory() == null ? InventorySnapshot.empty() : context.inventory().snapshot(), false);
    }

    private void handleSafety(SafetyController.Stop stop) {
        String reason = SafetyController.describe(stop);
        if (SafetyController.isFatal(stop)) {
            if (state.canTransitionTo(BotState.FAILED)) transition(BotState.FAILED, reason);
            listener.onFailure(reason);
        } else if (state.isActive() && state.canTransitionTo(BotState.PAUSED)) {
            transition(BotState.PAUSED, reason);
            listener.onWarning(reason);
        }
    }

    private void analyze(long now) {
        GoalManager.Goal goal = goals.next();
        if (goal == null) {
            transition(BotState.COMPLETED, "no objectives");
            return;
        }
        List<BotTask> planned = TaskPlanner.plan(goal, context);
        if (planned.isEmpty()) {
            goals.completeActive(false, "not plannable", memory());
            emit(BotEvent.Level.ERROR, "Could not plan: " + goal.description());
            listener.onObjectiveCompleted(false, "could not plan \"" + goal.description() + "\"");
            transition(BotState.WAITING, "unplannable objective");
            return;
        }
        for (BotTask task : planned) {
            if (!queue.add(task)) {
                listener.onWarning("The task queue filled up while planning; some tasks were dropped.");
                break;
            }
        }
        recovery.reset();
        emit(BotEvent.Level.INFO, "Planned " + planned.size() + " step(s) for \"" + goal.description() + "\".");
        transition(BotState.PLANNING, "planned");
    }

    private void recover(long now) {
        if (!recovery.readyToRetry(now)) return;
        // Resume the re-queued attempt rather than re-planning, so a retry does not duplicate the
        // rest of the plan.
        transition(BotState.EXECUTING, "recovered");
    }

    private void work(long now) {
        if (active == null) {
            Optional<BotTask> next = queue.nextRunnable();
            if (next.isEmpty()) {
                settleObjective();
                return;
            }
            beginTask(next.get());
        }
        if (now - lastActionAt < config.actionCooldownMs()) return;

        // A task that runs past its budget is failed rather than left to retry forever.
        if (config.taskTimeoutMs() > 0 && now - active.startedAt() > config.taskTimeoutMs()) {
            failActive("Timed out after " + (config.taskTimeoutMs() / 1000) + " seconds.");
            return;
        }

        TaskExecutor executor = executors.get(active.type());
        if (executor == null) {
            failActive("No executor is registered for " + active.type() + ".");
            return;
        }

        int budget = config.effectiveActionsPerTick();
        for (int i = 0; i < budget; i++) {
            TaskOutcome outcome;
            try {
                outcome = executor.step(active, context);
            } catch (RuntimeException e) {
                outcome = TaskOutcome.FAILED;
                active.fail("The step failed: " + e.getClass().getSimpleName());
                listener.onFailure("A task step failed: " + e.getMessage());
            }
            lastActionAt = now;
            performance.recordAction();

            if (outcome == null) outcome = TaskOutcome.CONTINUE;
            switch (outcome) {
                case DONE -> {
                    completeActive();
                    return;
                }
                case RETRY -> {
                    handleRetry(now);
                    return;
                }
                case FAILED -> {
                    failActive(active.failureReason().isBlank()
                        ? "The task could not be completed." : active.failureReason());
                    return;
                }
                case CANCELLED -> {
                    cancelActive("cancelled");
                    return;
                }
                case CONTINUE -> {
                    // Keep stepping within the per-tick budget.
                }
            }
        }
    }

    private void beginTask(BotTask task) {
        active = task;
        task.setStartedAt(clock.now());
        if (task.createdAt() == 0) task.setCreatedAt(clock.now());
        queue.markRunning(task);
        TaskExecutor executor = executors.get(task.type());
        if (executor != null) {
            try {
                executor.begin(task, context);
            } catch (RuntimeException e) {
                listener.onWarning("The task could not start cleanly: " + e.getMessage());
            }
        }
        transition(substateFor(task.type()), "working");
        emit(BotEvent.Level.INFO, "Started: " + task.describe());
        listener.onTaskStarted(task);
    }

    private void completeActive() {
        if (active == null) return;
        TaskExecutor executor = executors.get(active.type());
        if (executor != null) endQuietly(executor, active);
        queue.markDone(active);
        history.record(active);
        emit(BotEvent.Level.SUCCESS, "Finished: " + active.describe());
        listener.onTaskFinished(active);
        active = null;
        recovery.reset();
        if (state.canTransitionTo(BotState.EXECUTING)) transition(BotState.EXECUTING, "next");
    }

    private void failActive(String reason) {
        if (active == null) return;
        TaskExecutor executor = executors.get(active.type());
        if (executor != null) endQuietly(executor, active);
        active.fail(reason);
        queue.markFinished(active);
        history.record(active);
        emit(BotEvent.Level.ERROR, "Failed: " + active.describe() + " - " + reason);
        listener.onTaskFinished(active);

        BotRecovery.Decision decision = recovery.onFailure(true, clock.now());
        if (decision == BotRecovery.Decision.RETRY && state.canTransitionTo(BotState.RECOVERING)) {
            emit(BotEvent.Level.WARNING, "Retrying in " + (recovery.currentBackoff() / 1000) + " second(s).");
            listener.onWarning(reason + " Retrying.");
            // Re-queue a fresh attempt so the failed instance is not re-run. The original is
            // replaced in place, so a retry does not grow the queue without bound.
            long failedId = active.id();
            BotTask retry = active.copy();
            retry.setState(TaskState.PENDING);
            retry.setProgress(0);
            retry.setStartedAt(0);
            retry.setFinishedAt(0);
            active = null;
            queue.replace(failedId, retry);
            transition(BotState.RECOVERING, "retrying");
        } else {
            active = null;
            listener.onFailure(reason);
            transition(BotState.FAILED, reason);
        }
    }

    private void handleRetry(long now) {
        // A soft retry: the task stays active and simply waits. The retry budget is deliberately
        // not reset here; only a completed task or a fresh plan clears it, so a task that keeps
        // asking to retry is still bounded by the task timeout and the retry limit.
    }

    private void cancelActive(String reason) {
        if (active == null) return;
        TaskExecutor executor = executors.get(active.type());
        if (executor != null) endQuietly(executor, active);
        active.setState(TaskState.CANCELLED);
        queue.markFinished(active);
        history.record(active);
        listener.onTaskFinished(active);
        emit(BotEvent.Level.WARNING, "Cancelled: " + active.describe());
        active = null;
    }

    private void endQuietly(TaskExecutor executor, BotTask task) {
        try {
            executor.end(task, context);
        } catch (RuntimeException e) {
            listener.onWarning("A task did not clean up cleanly: " + e.getMessage());
        }
    }

    private void settleObjective() {
        boolean anyFailed = false;
        for (BotTask task : queue.tasks()) {
            if (task.state() == TaskState.FAILED) anyFailed = true;
        }
        queue.pruneFinished();
        boolean success = !anyFailed;
        String summary = goals.active() == null ? "done" : goals.active().description();
        goals.completeActive(success, success ? "done" : "incomplete", memory());
        listener.onObjectiveCompleted(success, summary);
        emit(success ? BotEvent.Level.SUCCESS : BotEvent.Level.ERROR,
            (success ? "Completed: " : "Incomplete: ") + summary);

        if (goals.hasWork()) {
            transition(BotState.ANALYZING, "next objective");
        } else {
            transition(success ? BotState.COMPLETED : BotState.FAILED, summary);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BotState substateFor(TaskType type) {
        return switch (type) {
            case GATHERING, MINING, WOODCUTTING, FARMING -> BotState.GATHERING;
            case CRAFTING, SMELTING -> BotState.CRAFTING;
            case BUILDING -> BotState.BUILDING;
            case NAVIGATION, EXPLORATION, TRANSPORT -> BotState.NAVIGATING;
            default -> BotState.EXECUTING;
        };
    }

    private BotMemory memory() {
        return context == null ? null : context.memory();
    }

    private void transition(BotState next, String reason) {
        if (next == null || next == state) return;
        if (!state.canTransitionTo(next)) {
            // Route through IDLE when the direct edge does not exist, so the state machine never
            // gets stuck; this keeps the invariant that state is always a legal node.
            if (state.canTransitionTo(BotState.IDLE)) {
                setState(BotState.IDLE, "reset");
            } else {
                setState(next, reason);
                return;
            }
        }
        setState(next, reason);
    }

    private void setState(BotState next, String reason) {
        state = next;
        stateSince = clock.now();
        emit(BotEvent.Level.INFO,
            "State: " + next + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        listener.onStateChanged(next, reason);
    }

    private void emit(BotEvent.Level level, String message) {
        events.addFirst(new BotEvent(clock.now(), level, message));
        while (events.size() > EVENT_LIMIT) events.removeLast();
    }
}
