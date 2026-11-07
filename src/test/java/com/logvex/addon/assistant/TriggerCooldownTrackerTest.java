package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerCooldownTracker;
import com.logvex.addon.assistant.core.TriggerRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cooldown and session-limit accounting, and reset/retain behaviour. */
class TriggerCooldownTrackerTest {

    private TriggerRule rule(String pattern, long cooldown, int limit) {
        return new TriggerRule(pattern, false, false, "", "reply",
            TriggerRule.ReplyMode.CHAT, cooldown, limit, false, false, true);
    }

    @Test
    @DisplayName("a zero cooldown never suppresses")
    void zeroCooldown() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        TriggerRule rule = rule("ping", 0, 0);
        assertTrue(tracker.tryFire(rule, 1L));
        assertTrue(tracker.tryFire(rule, 2L));
        assertEquals(2, tracker.count("ping"));
    }

    @Test
    @DisplayName("a cooldown suppresses fires inside the window")
    void cooldownWindow() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        TriggerRule rule = rule("ping", 1_000, 0);
        assertTrue(tracker.tryFire(rule, 1_000L));
        assertFalse(tracker.tryFire(rule, 1_500L));
        assertTrue(tracker.tryFire(rule, 2_000L));
    }

    @Test
    @DisplayName("the session limit stops a rule after N fires")
    void sessionLimit() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        TriggerRule rule = rule("ping", 0, 2);
        assertTrue(tracker.tryFire(rule, 1L));
        assertTrue(tracker.tryFire(rule, 2L));
        assertFalse(tracker.tryFire(rule, 3L));
    }

    @Test
    @DisplayName("canFire does not consume a fire")
    void canFireIsReadOnly() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        TriggerRule rule = rule("ping", 1_000, 0);
        assertTrue(tracker.canFire(rule, 1_000L));
        assertTrue(tracker.canFire(rule, 1_000L));
        assertEquals(0, tracker.count("ping"));
    }

    @Test
    @DisplayName("retain drops state for patterns that are no longer configured")
    void retainPrunes() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        tracker.tryFire(rule("ping", 0, 0), 1L);
        tracker.tryFire(rule("pong", 0, 0), 1L);

        tracker.retain(List.of("ping"));
        assertEquals(1, tracker.count("ping"));
        assertEquals(0, tracker.count("pong"));
    }

    @Test
    @DisplayName("reset clears every counter")
    void reset() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        tracker.tryFire(rule("ping", 0, 0), 1L);
        tracker.reset();
        assertEquals(0, tracker.count("ping"));
    }

    @Test
    @DisplayName("a null rule never fires")
    void nullRule() {
        TriggerCooldownTracker tracker = new TriggerCooldownTracker();
        assertFalse(tracker.tryFire(null, 1L));
        assertFalse(tracker.canFire(null, 1L));
    }
}
