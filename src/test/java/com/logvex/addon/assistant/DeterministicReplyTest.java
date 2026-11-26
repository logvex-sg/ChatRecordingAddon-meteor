package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.GreetingPools;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two rules that make a trigger reply readable: it never varies, and it is whispered.
 *
 * <p>Both were broken by accident before. A pipe meant "pick an alternative at random" to the
 * parser and "field separator" to a reader, so {@code TPS {tps} | MSPT {mspt}} printed one of the
 * three fields at random. A shipped reply looked broken roughly two times in three. These tests
 * fail if either behaviour returns.
 */
class DeterministicReplyTest {

    @Test
    @DisplayName("a shipped reply never contains a pipe, which would read as a field separator")
    void shippedRepliesUseNoPipe() {
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertFalse(rule.reply().contains("|"),
                "'" + rule.pattern() + "' uses a pipe in its reply: " + rule.reply());
        }
    }

    @Test
    @DisplayName("every shipped rule whispers, so a busy chat does not see the answer")
    void everyShippedRuleWhispers() {
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertEquals(TriggerRule.ReplyMode.PRIVATE, rule.mode(),
                "'" + rule.pattern() + "' should whisper");
        }
    }

    @Test
    @DisplayName("a new rule defaults to a whisper")
    void newRuleDefaultsToWhisper() {
        assertEquals(TriggerRule.ReplyMode.PRIVATE, TriggerRule.of("hi", "hello").mode());
        assertEquals(TriggerRule.ReplyMode.PRIVATE,
            TriggerRule.decode("hi;reply=hello").mode(), "an unset mode whispers");
    }

    @Test
    @DisplayName("the same question always produces the same reply")
    void repliesAreStable() {
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        for (String question : List.of("!tps", "!status", "!ram", "hello", "where am i")) {
            TriggerRule rule = set.firstMatch("Steve", question);
            assertTrue(rule != null, "'" + question + "' should match a shipped rule");
            Set<String> outputs = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                outputs.add(rule.chosenReply(null));
            }
            assertEquals(1, outputs.size(),
                "'" + question + "' produced " + outputs.size() + " different replies: " + outputs);
        }
    }

    @Test
    @DisplayName("a stored pipe reply resolves to one line and never prints the pipe")
    void legacyPipeReplyIsNotRandom() {
        // A rule saved by an older build may still carry alternatives.
        TriggerRule legacy = new TriggerRule("hi", false, false, "", "One|Two|Three",
            TriggerRule.ReplyMode.PRIVATE, 0L, 0, false, false, true);

        Set<String> outputs = new HashSet<>();
        for (int i = 0; i < 100; i++) outputs.add(legacy.chosenReply(null));

        assertEquals(1, outputs.size(), "a legacy reply must not vary: " + outputs);
        assertFalse(outputs.iterator().next().contains("|"), "the pipe is not printed");
    }

    @Test
    @DisplayName("no greeting pool offers several alternatives")
    void greetingPoolsAreSingleLines() {
        for (GreetingPools.Bucket bucket : GreetingPools.buckets()) {
            assertFalse(bucket.pool().contains("|"),
                "the " + bucket.id() + " greeting still has alternatives: " + bucket.pool());
        }
    }

    @Test
    @DisplayName("the greeting for a time of day is stable")
    void greetingIsStable() {
        LocalTime morning = LocalTime.of(6, 0);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(GreetingPools.greeting(null, morning, null));
        assertEquals(1, seen.size(), "the morning greeting varied: " + seen);
    }

    @Test
    @DisplayName("a whispered reply reaches the speaker through the private-message path")
    void whisperIsTheDelivery() {
        AssistantRuntime runtime = new AssistantRuntime(
            new com.logvex.addon.assistant.core.AssistantMemory());
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS
            .withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(List.of(TriggerRule.of("hello bot", "Hello {player}!"))));

        AssistantBrain.Decision decision = runtime.handle("Notch", "hello bot", 1_000L);

        assertEquals(AssistantBrain.Delivery.PRIVATE, decision.delivery(),
            "the default reply goes to the asker only");
        assertEquals("Hello Notch!", decision.text());
    }
}
