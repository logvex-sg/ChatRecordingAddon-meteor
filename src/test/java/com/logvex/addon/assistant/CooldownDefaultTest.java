package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-command cooldown default and how it behaves.
 *
 * <p>A cooldown is the difference between an assistant that answers a question twice and one that
 * feels broken, so the default and the boundaries are pinned here rather than left to the GUI.
 */
class CooldownDefaultTest {

    private static AssistantRuntime runtime(TriggerRule... rules) {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200).withAudience(TriggerAudience.EVERYONE));
        runtime.setTriggers(TriggerSet.of(List.of(rules)));
        runtime.setDuplicateFilter(false);
        runtime.setPermissions(PermissionSet.permissive());
        return runtime;
    }

    private static TriggerRule rule(String pattern, long cooldown) {
        return new TriggerRule(pattern, false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, cooldown, 0, false, false, true);
    }

    @Test
    @DisplayName("the default cooldown is one second")
    void defaultIsOneSecond() {
        assertEquals(1_000L, TriggerRule.DEFAULT_COOLDOWN_MS);
        // The convenience factory is what most call sites use, so it must carry the default too.
        assertEquals(1_000L, TriggerRule.of("hi", "hello").cooldownMs());
    }

    @Test
    @DisplayName("a rule fires again as soon as its cooldown has expired")
    void firesAgainAfterCooldown() {
        AssistantRuntime runtime = runtime(rule("ping", TriggerRule.DEFAULT_COOLDOWN_MS));

        assertTrue(runtime.handle("p", "ping", 1_000L).acts(), "the first ask is answered");
        assertFalse(runtime.handle("p", "ping", 1_500L).acts(), "half a second later is too soon");
        assertTrue(runtime.handle("p", "ping", 2_000L).acts(),
            "exactly one second later is allowed again");
    }

    @Test
    @DisplayName("a rule cannot fire repeatedly inside its cooldown")
    void cannotSpamInsideCooldown() {
        AssistantRuntime runtime = runtime(rule("ping", TriggerRule.DEFAULT_COOLDOWN_MS));

        assertTrue(runtime.handle("p", "ping", 0L).acts());
        for (long offset = 100; offset < 1_000; offset += 100) {
            assertFalse(runtime.handle("p", "ping", offset).acts(),
                "a repeat at " + offset + " ms is inside the cooldown");
        }
    }

    @Test
    @DisplayName("different commands keep independent cooldowns")
    void cooldownsAreIndependent() {
        AssistantRuntime runtime = runtime(rule("ping", 1_000L), rule("pong", 1_000L));

        assertTrue(runtime.handle("p", "ping", 1_000L).acts());
        assertTrue(runtime.handle("p", "pong", 1_000L).acts(),
            "a second command is not blocked by the first one's cooldown");
        assertFalse(runtime.handle("p", "ping", 1_200L).acts());
        assertFalse(runtime.handle("p", "pong", 1_200L).acts());
        assertTrue(runtime.handle("p", "ping", 2_000L).acts());
        assertTrue(runtime.handle("p", "pong", 2_000L).acts());
    }

    @Test
    @DisplayName("a custom cooldown survives an encode and decode round trip")
    void customCooldownSurvivesStorage() {
        // This is the upgrade path: an existing rule's cooldown is stored in its encoded form, and
        // decoding must hand back exactly what was saved rather than the new default.
        TriggerRule custom = rule("ping", 7_500L);

        TriggerRule decoded = TriggerRule.decode(custom.encode());

        assertEquals(7_500L, decoded.cooldownMs(),
            "a user's own cooldown must not be reset by the new default");
    }

    @Test
    @DisplayName("a stored rule with no cooldown field falls back to the default")
    void missingCooldownUsesDefault() {
        TriggerRule decoded = TriggerRule.decode("ping;reply=pong");

        assertEquals(TriggerRule.DEFAULT_COOLDOWN_MS, decoded.cooldownMs());
    }

    @Test
    @DisplayName("a zero cooldown still means no cooldown, not the default")
    void zeroStaysZero() {
        TriggerRule decoded = TriggerRule.decode("ping;reply=pong;cooldown=0");

        assertEquals(0L, decoded.cooldownMs());
        AssistantRuntime runtime = runtime(decoded);
        assertTrue(runtime.handle("p", "ping", 1L).acts());
        assertTrue(runtime.handle("p", "ping", 2L).acts(), "zero means every ask is answered");
    }
}
