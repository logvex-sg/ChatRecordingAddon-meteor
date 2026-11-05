package com.logvex.addon.bot.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * The bot's execution state. Every transition goes through {@link #canTransitionTo(BotState)}; the
 * controller refuses anything else, so the bot can never be "executing" while it is disabled or
 * jump straight from a failure back into work without recovering first.
 */
public enum BotState {
    DISABLED,
    IDLE,
    INITIALIZING,
    ANALYZING,
    PLANNING,
    EXECUTING,
    NAVIGATING,
    GATHERING,
    BUILDING,
    CRAFTING,
    WAITING,
    PAUSED,
    RECOVERING,
    COMPLETED,
    FAILED;

    /** States in which the controller is actively driving work. */
    public boolean isActive() {
        return switch (this) {
            case INITIALIZING, ANALYZING, PLANNING, EXECUTING, NAVIGATING, GATHERING, BUILDING,
                CRAFTING, WAITING, RECOVERING -> true;
            default -> false;
        };
    }

    /** States from which work can be resumed. */
    public boolean isResumable() {
        return this == PAUSED || this == WAITING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == DISABLED || this == IDLE;
    }

    /**
     * The set of states this state may move to. The graph is deliberately explicit rather than
     * "anything goes", because an unvalidated transition is how a state machine silently corrupts
     * its own invariants.
     */
    public Set<BotState> allowedNext() {
        return switch (this) {
            case DISABLED -> EnumSet.of(IDLE);
            case IDLE -> EnumSet.of(INITIALIZING, PAUSED, DISABLED);
            case INITIALIZING -> EnumSet.of(ANALYZING, PAUSED, FAILED, IDLE);
            case ANALYZING -> EnumSet.of(PLANNING, WAITING, PAUSED, FAILED);
            case PLANNING -> EnumSet.of(EXECUTING, NAVIGATING, GATHERING, BUILDING, CRAFTING,
                COMPLETED, FAILED, PAUSED, WAITING);
            case EXECUTING, NAVIGATING, GATHERING, BUILDING, CRAFTING ->
                EnumSet.of(EXECUTING, NAVIGATING, GATHERING, BUILDING, CRAFTING, ANALYZING,
                    PLANNING, WAITING, PAUSED, RECOVERING, COMPLETED, FAILED);
            case WAITING -> EnumSet.of(ANALYZING, PLANNING, EXECUTING, NAVIGATING, GATHERING,
                BUILDING, CRAFTING, PAUSED, COMPLETED, FAILED, IDLE);
            case PAUSED -> EnumSet.of(INITIALIZING, ANALYZING, PLANNING, EXECUTING, NAVIGATING,
                GATHERING, BUILDING, CRAFTING, IDLE, FAILED);
            case RECOVERING -> EnumSet.of(ANALYZING, PLANNING, EXECUTING, NAVIGATING, GATHERING,
                BUILDING, CRAFTING, PAUSED, WAITING, FAILED, IDLE);
            case COMPLETED, FAILED -> EnumSet.of(IDLE, INITIALIZING, DISABLED);
        };
    }

    public boolean canTransitionTo(BotState next) {
        return next != null && allowedNext().contains(next);
    }

    @Override
    public String toString() {
        String lower = name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
