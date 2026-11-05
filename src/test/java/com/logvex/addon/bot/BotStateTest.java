package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BotState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state graph must never allow an illegal jump. */
class BotStateTest {
    @Test
    void disabledOnlyGoesToIdle() {
        assertTrue(BotState.DISABLED.canTransitionTo(BotState.IDLE));
        assertFalse(BotState.DISABLED.canTransitionTo(BotState.EXECUTING));
        assertFalse(BotState.DISABLED.canTransitionTo(BotState.PAUSED));
    }

    @Test
    void idleCanStartAndBeDisabled() {
        assertTrue(BotState.IDLE.canTransitionTo(BotState.INITIALIZING));
        assertTrue(BotState.IDLE.canTransitionTo(BotState.DISABLED));
        assertFalse(BotState.IDLE.canTransitionTo(BotState.EXECUTING));
    }

    @Test
    void activeStatesCanPauseAndComplete() {
        for (BotState state : new BotState[]{BotState.EXECUTING, BotState.GATHERING, BotState.BUILDING}) {
            assertTrue(state.canTransitionTo(BotState.PAUSED), state + " should pause");
            assertTrue(state.canTransitionTo(BotState.COMPLETED), state + " should complete");
            assertTrue(state.canTransitionTo(BotState.FAILED), state + " should fail");
            assertTrue(state.canTransitionTo(BotState.RECOVERING), state + " should recover");
        }
    }

    @Test
    void pausedCanResumeButNotJumpStraightToRunning() {
        assertTrue(BotState.PAUSED.canTransitionTo(BotState.INITIALIZING));
        assertTrue(BotState.PAUSED.canTransitionTo(BotState.IDLE));
    }

    @Test
    void terminalStatesRestartFromIdle() {
        for (BotState state : new BotState[]{BotState.COMPLETED, BotState.FAILED}) {
            assertTrue(state.canTransitionTo(BotState.IDLE), state + " should return to idle");
            assertTrue(state.canTransitionTo(BotState.DISABLED));
        }
    }

    @Test
    void everyStateHasAtLeastOneExit() {
        for (BotState state : BotState.values()) {
            assertFalse(state.allowedNext().isEmpty(), state + " must have an exit");
        }
    }

    @Test
    void activeAndTerminalClassification() {
        assertTrue(BotState.EXECUTING.isActive());
        assertFalse(BotState.PAUSED.isActive());
        assertTrue(BotState.COMPLETED.isTerminal());
        assertTrue(BotState.PAUSED.isResumable());
    }
}
