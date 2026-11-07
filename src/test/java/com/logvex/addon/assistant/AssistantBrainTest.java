package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assistant's decision path: triggers, memory commands, cooldowns and rate limits. */
class AssistantBrainTest {

    private AssistantRuntime runtime(TriggerRule... rules) {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(List.of(rules)));
        runtime.setConfig(AssistantConfig.DEFAULTS);
        return runtime;
    }

    @Test
    @DisplayName("a matching trigger produces a chat decision with an expanded reply")
    void triggerReplies() {
        AssistantRuntime runtime = runtime(TriggerRule.of("hello bot", "Hello {player}!"));
        AssistantBrain.Decision decision = runtime.handle("Notch", "hello bot", 1_000L);

        assertTrue(decision.acts());
        assertEquals(AssistantBrain.Delivery.CHAT, decision.delivery());
        assertEquals("Hello Notch!", decision.text());
        assertNotNull(decision.rule());
    }

    @Test
    @DisplayName("a message that matches nothing is ignored")
    void noMatchIgnored() {
        AssistantRuntime runtime = runtime(TriggerRule.of("hello bot", "hi"));
        AssistantBrain.Decision decision = runtime.handle("Notch", "just chatting", 1_000L);
        assertFalse(decision.acts());
        assertEquals(0, runtime.handled());
        assertEquals(1, runtime.ignored());
    }

    @Test
    @DisplayName("a rule's cooldown suppresses a rapid second match")
    void cooldownSuppresses() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 5_000L, 0, false, false, true);
        AssistantRuntime runtime = runtime(rule);

        assertTrue(runtime.handle("p", "ping", 1_000L).acts());
        assertFalse(runtime.handle("p", "ping", 2_000L).acts());
        assertTrue(runtime.handle("p", "ping", 7_000L).acts());
    }

    @Test
    @DisplayName("a session limit stops a rule after N replies")
    void sessionLimit() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 2, false, false, true);
        AssistantRuntime runtime = runtime(rule);

        assertTrue(runtime.handle("p", "ping", 1L).acts());
        assertTrue(runtime.handle("p", "ping", 2L).acts());
        assertFalse(runtime.handle("p", "ping", 3L).acts());
    }

    @Test
    @DisplayName("the global rate limit caps replies per minute")
    void globalRateLimit() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true);
        AssistantRuntime runtime = runtime(rule);
        runtime.setConfig(new AssistantConfig("A", true, true, "!", TriggerRule.ReplyMode.NOTIFY,
            true, 2, 200));

        assertTrue(runtime.handle("p", "ping", 1_000L).acts());
        assertTrue(runtime.handle("p", "ping", 1_100L).acts());
        assertFalse(runtime.handle("p", "ping", 1_200L).acts());
        assertEquals(1, runtime.rateLimited());
        // Once the window slides past the first two replies, the budget refills.
        assertTrue(runtime.handle("p", "ping", 70_000L).acts());
    }

    @Test
    @DisplayName("a trigger takes precedence over a memory command")
    void triggerBeatsCommand() {
        AssistantRuntime runtime = runtime(TriggerRule.of("!remember", "triggered"));
        AssistantBrain.Decision decision = runtime.handle("p", "!remember x = y", 1_000L);
        assertEquals("triggered", decision.text());
    }

    @Test
    @DisplayName("!remember stores a fact and answers with a notify by default")
    void rememberCommand() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision decision = runtime.handle("p", "!remember home = the castle", 1_000L);

        assertTrue(decision.acts());
        assertEquals(AssistantBrain.Delivery.NOTIFY, decision.delivery());
        assertEquals("the castle", runtime.memory().fact("home").orElseThrow().value());
    }

    @Test
    @DisplayName("!fact reads a fact and !forget removes it")
    void factAndForget() {
        AssistantRuntime runtime = runtime();
        runtime.handle("p", "!remember color = blue", 1L);

        assertEquals("color = blue", runtime.handle("p", "!fact color", 2L).text());
        assertTrue(runtime.handle("p", "!forget color", 3L).acts());
        assertTrue(runtime.memory().fact("color").isEmpty());
    }

    @Test
    @DisplayName("a memory command with the wrong shape is answered with usage, not an error")
    void malformedCommand() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision decision = runtime.handle("p", "!remember noequalsign", 1L);
        assertTrue(decision.acts());
        assertTrue(decision.text().toLowerCase(java.util.Locale.ROOT).contains("usage"));
    }

    @Test
    @DisplayName("turning memory commands off makes them inert")
    void memoryCommandsCanBeDisabled() {
        AssistantRuntime runtime = runtime();
        runtime.setConfig(new AssistantConfig("A", true, false, "!", TriggerRule.ReplyMode.NOTIFY,
            true, 20, 200));
        assertFalse(runtime.handle("p", "!remember x = y", 1L).acts());
    }

    @Test
    @DisplayName("a remembered exchange lands in history when enabled")
    void historyRecorded() {
        AssistantRuntime runtime = runtime(TriggerRule.of("ping", "pong"));
        AssistantBrain.Decision decision = runtime.handle("Notch", "ping", 1_000L);
        assertTrue(decision.remember());
        assertEquals(1, runtime.memory().historySize());
        assertEquals("ping", runtime.memory().history(1).get(0).question());
    }

    @Test
    @DisplayName("resetSession clears counters but keeps facts")
    void resetSessionKeepsFacts() {
        AssistantRuntime runtime = runtime(TriggerRule.of("ping", "pong"));
        runtime.handle("p", "ping", 1L);
        runtime.memory().rememberFact("home", "x", 1L);

        runtime.resetSession();
        assertEquals(0, runtime.handled());
        assertTrue(runtime.memory().fact("home").isPresent());
    }

    @Test
    @DisplayName("a long reply is clamped to the configured maximum")
    void replyClamped() {
        AssistantRuntime runtime = runtime(TriggerRule.of("ping", "x".repeat(500)));
        runtime.setConfig(new AssistantConfig("A", true, true, "!", TriggerRule.ReplyMode.NOTIFY,
            true, 20, 50));
        AssistantBrain.Decision decision = runtime.handle("p", "ping", 1L);
        assertEquals(50, decision.text().length());
    }

    @Test
    @DisplayName("a silent rule records memory but produces no visible output")
    void silentRule() {
        TriggerRule rule = new TriggerRule("secret", false, false, "", "not spoken",
            TriggerRule.ReplyMode.SILENT, 0L, 0, false, true, true);
        AssistantRuntime runtime = runtime(rule);
        AssistantBrain.Decision decision = runtime.handle("p", "a secret", 1L);
        assertEquals(AssistantBrain.Delivery.SILENT, decision.delivery());
        assertEquals(1, runtime.memory().historySize());
    }
}
