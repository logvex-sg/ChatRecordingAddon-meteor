package com.logvex.addon.automation.core;

import java.util.List;

/**
 * Schedules small, human-like actions so the player is not kicked for idling.
 *
 * <p>The controller only decides <em>which</em> action to perform and <em>when</em>; the Minecraft
 * layer performs it. Actions are chosen round-robin from the enabled set and each is separated by a
 * randomised interval in {@code [minIntervalMs, maxIntervalMs]}, so the pattern is not a fixed
 * heartbeat. The randomness is injectable, which keeps the schedule reproducible in tests.
 */
public final class AntiAfkController {
    /** The small actions the module can perform. */
    public enum Action {
        LOOK_AROUND,
        JUMP,
        SNEAK,
        SWING,
        WALK_FORWARD,
        CYCLE_HOTBAR
    }

    private final java.util.Random random;
    private final List<Action> actions = new java.util.ArrayList<>();

    private boolean enabled;
    private long minIntervalMs;
    private long maxIntervalMs;

    private long nextActionAt;
    private int actionIndex;

    public AntiAfkController(long minIntervalMs, long maxIntervalMs, java.util.Random random) {
        this.minIntervalMs = Math.max(500, minIntervalMs);
        this.maxIntervalMs = Math.max(this.minIntervalMs, maxIntervalMs);
        this.random = random == null ? new java.util.Random() : random;
        for (Action action : Action.values()) actions.add(action);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setIntervals(long minMs, long maxMs) {
        this.minIntervalMs = Math.max(500, minMs);
        this.maxIntervalMs = Math.max(this.minIntervalMs, maxMs);
    }

    /** Restricts the action set to those the user enabled, in enum order. */
    public void setActions(List<Action> enabledActions) {
        actions.clear();
        if (enabledActions == null || enabledActions.isEmpty()) {
            for (Action action : Action.values()) actions.add(action);
            return;
        }
        for (Action action : Action.values()) {
            if (enabledActions.contains(action)) actions.add(action);
        }
    }

    public List<Action> actions() {
        return java.util.Collections.unmodifiableList(actions);
    }

    public void reset() {
        nextActionAt = 0;
        actionIndex = 0;
    }

    /** Arms the schedule for the first time, e.g. when the module is enabled. */
    public void arm(long now) {
        nextActionAt = now + delay();
    }

    private long delay() {
        if (maxIntervalMs <= minIntervalMs) return minIntervalMs;
        long span = maxIntervalMs - minIntervalMs;
        return minIntervalMs + (long) (random.nextDouble() * span);
    }

    /**
     * Returns the next action to perform, or {@code null} when it is not time yet.
     *
     * <p>The caller must perform the returned action; the controller only advances its schedule once
     * it hands one out, so a dropped action cannot silently desynchronise the cadence.
     */
    public Action next(long now) {
        if (!enabled || actions.isEmpty()) return null;
        if (nextActionAt == 0) {
            arm(now);
            return null;
        }
        if (now < nextActionAt) return null;

        Action action = actions.get(actionIndex % actions.size());
        actionIndex++;
        nextActionAt = now + delay();
        return action;
    }

    /** Milliseconds until the next action, or -1 when not scheduled. */
    public long millisUntilNext(long now) {
        if (!enabled || nextActionAt == 0) return -1;
        return Math.max(0, nextActionAt - now);
    }

    public String status(long now) {
        if (!enabled) return "off";
        if (nextActionAt == 0) return "armed";
        return "next in " + (millisUntilNext(now) / 1000) + "s (" + actions.size() + " actions)";
    }
}
