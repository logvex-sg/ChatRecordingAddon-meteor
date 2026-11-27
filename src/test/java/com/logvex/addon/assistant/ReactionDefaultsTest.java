package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.IgnoredPhrases;
import com.logvex.addon.assistant.core.ReactionCategory;
import com.logvex.addon.assistant.core.ReactionGate;
import com.logvex.addon.assistant.core.ReactionLog;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported bug: the assistant answered {@code bye}, {@code gtg} and {@code cya} without being
 * asked to, and there was no usable way to stop it.
 *
 * <p>These tests are written from the user's side. Each one says "this is what a person sees" rather
 * than "this method returns that", because the defect was not in any single method — every method
 * worked. The table was simply armed by default and the switches were the wrong size.
 */
class ReactionDefaultsTest {

    private static final long NOW = 1_700_000_000_000L;

    private static AssistantRuntime runtimeWith(ReactionGate gate, TriggerRule... rules) {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200).withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(List.of(rules)));
        runtime.setGate(gate);
        return runtime;
    }

    /** A gate built the way the module builds it: the category defaults, nothing else configured. */
    private static ReactionGate defaultGate() {
        return new ReactionGate(ReactionCategory.defaultsEnabled(), IgnoredPhrases.NONE, true, 0L);
    }

    @Test
    @DisplayName("a fresh install does not answer a farewell")
    void freshInstallIsSilentForFarewells() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200).withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setGate(defaultGate());

        for (String line : List.of("bye", "cya", "gtg", "goodbye", "see you later")) {
            assertFalse(runtime.handle("Steve", line, NOW).acts(),
                "'" + line + "' was answered on a fresh install");
        }
    }

    @Test
    @DisplayName("a fresh install still answers a prefixed command")
    void freshInstallStillAnswersCommands() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 400).withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setGate(defaultGate());

        assertTrue(runtime.handle("Steve", "!tps", NOW).acts(),
            "a prefixed command is an explicit request and must still be answered");
    }

    @Test
    @DisplayName("turning the farewell category on makes a farewell answer again")
    void categorySwitchRestoresTheReply() {
        TriggerRule goodbye = TriggerRule.preconfigured().stream()
            .filter(rule -> rule.pattern().equals("goodbye")).findFirst().orElseThrow();
        assertEquals(ReactionCategory.FAREWELLS, goodbye.category(),
            "the shipped goodbye rule must be in the farewell category");

        Set<ReactionCategory> on = new java.util.HashSet<>(ReactionCategory.defaultsEnabled());
        ReactionGate gate = new ReactionGate(on, IgnoredPhrases.NONE, true, 0L);
        AssistantRuntime off = runtimeWith(gate, goodbye);
        assertFalse(off.handle("Steve", "cya", NOW).acts(), "farewells are off by default");

        on.add(ReactionCategory.FAREWELLS);
        AssistantRuntime armed = runtimeWith(new ReactionGate(on, IgnoredPhrases.NONE, true, 0L),
            goodbye);
        assertTrue(armed.handle("Steve", "cya", NOW).acts(),
            "switching the category on must bring the rule back");
    }

    @Test
    @DisplayName("every conversational category is off on a fresh install")
    void everyConversationalCategoryStartsOff() {
        for (ReactionCategory category : ReactionCategory.all()) {
            if (!category.conversational()) {
                assertTrue(category.defaultEnabled(),
                    category.id() + " is an explicit-request category and should start on");
                continue;
            }
            assertFalse(category.defaultEnabled(),
                category.id() + " is conversational and must start off");
        }
    }

    @Test
    @DisplayName("no shipped conversational rule can answer while its category is off")
    void noConversationalRuleLeaksThrough() {
        ReactionGate gate = defaultGate();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (!rule.category().conversational()) continue;
            ReactionGate.Verdict verdict = gate.check(rule, "Steve", rule.pattern(), NOW);
            assertFalse(verdict.allowed(),
                "'" + rule.pattern() + "' (" + rule.category().id() + ") was allowed by default");
            assertFalse(verdict.reason().isBlank(),
                "'" + rule.pattern() + "' was refused without a reason");
        }
    }

    @Test
    @DisplayName("an ignored word silences a message even when its category is on")
    void ignoredWordWins() {
        TriggerRule goodbye = TriggerRule.preconfigured().stream()
            .filter(rule -> rule.pattern().equals("goodbye")).findFirst().orElseThrow();
        Set<ReactionCategory> all = Set.of(ReactionCategory.values());
        ReactionGate gate = new ReactionGate(all, IgnoredPhrases.of(List.of("gg")), true, 0L);

        AssistantRuntime runtime = runtimeWith(gate, goodbye);
        assertFalse(runtime.handle("Steve", "gg", NOW).acts(), "the ignored word did not silence it");

        // The same rule still answers when the ignored word is absent, so the filter is not simply
        // switching the rule off.
        assertTrue(runtime.handle("Steve", "cya", NOW + 5_000).acts(),
            "an ignored word must not disable the whole rule");
    }

    @Test
    @DisplayName("an ignored word matches whole words only")
    void ignoredWordIsWholeWord() {
        IgnoredPhrases ignored = IgnoredPhrases.of(List.of("gg"));

        assertTrue(ignored.matches("gg"), "the word itself must match");
        assertTrue(ignored.matches("well gg then"), "a word inside a sentence must match");
        assertFalse(ignored.matches("egg"), "'egg' contains 'gg' but is a different word");
        assertFalse(ignored.matches("suggest"), "'suggest' contains 'gg' but is a different word");
    }

    @Test
    @DisplayName("the master switch silences everything, including commands")
    void masterSwitchSilencesEverything() {
        ReactionGate gate = new ReactionGate(Set.of(ReactionCategory.values()), IgnoredPhrases.NONE,
            false, 0L);
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertFalse(gate.check(rule, "Steve", rule.pattern(), NOW).allowed(),
                "'" + rule.pattern() + "' answered with the master switch off");
        }
    }

    @Test
    @DisplayName("a mute silences reactions until it expires, then lets them back")
    void muteExpires() {
        ReactionGate gate = new ReactionGate(Set.of(ReactionCategory.values()), IgnoredPhrases.NONE,
            true, NOW + 10_000L);

        assertFalse(gate.check(TriggerRule.of("hi", "hello"), "Steve", "hi", NOW).allowed(),
            "a reaction fired inside the mute");
        assertTrue(gate.check(TriggerRule.of("hi", "hello"), "Steve", "hi", NOW + 11_000L).allowed(),
            "the reaction did not come back after the mute expired");
    }

    @Test
    @DisplayName("a refusal says why, so a user can act on it")
    void refusalExplainsItself() {
        TriggerRule goodbye = TriggerRule.preconfigured().stream()
            .filter(rule -> rule.pattern().equals("goodbye")).findFirst().orElseThrow();
        ReactionGate gate = defaultGate();

        ReactionGate.Verdict verdict = gate.check(goodbye, "Steve", "cya", NOW);

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().toLowerCase(java.util.Locale.ROOT).contains("farewell"),
            "the reason should name the category that is off, was: " + verdict.reason());
    }

    @Test
    @DisplayName("a skipped reaction is recorded, not silently dropped")
    void skippedReactionsAreRecorded() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200).withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setGate(defaultGate());

        assertFalse(runtime.handle("Steve", "cya", NOW).acts());

        ReactionLog log = runtime.reactionLog();
        assertEquals(1, log.skipped(), "the refused reaction was not recorded");
        ReactionLog.Entry entry = log.recent(1).get(0);
        assertEquals(ReactionLog.Outcome.SKIPPED, entry.outcome());
        assertEquals("goodbye", entry.pattern(), "the log should name the rule that matched");
        assertFalse(entry.reason().isBlank(), "the log should say why it was skipped");
    }

    @Test
    @DisplayName("a delivered reaction is recorded as a reply")
    void deliveredReactionsAreRecorded() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.basic("A", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200).withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setGate(defaultGate());

        assertTrue(runtime.handle("Steve", "!tps", NOW).acts());

        assertEquals(1, runtime.reactionLog().replied());
        assertEquals("!tps", runtime.reactionLog().recent(1).get(0).pattern());
    }

    @Test
    @DisplayName("the gate travels with the rules, so a path holding only a set is still gated")
    void gateTravelsWithTheSet() {
        // This is the structural half of the fix. A call site that only has a TriggerSet - and there
        // are several - cannot bypass the switches, because the set consults the gate itself.
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured())
            .withGate(new ReactionGate(Set.of(ReactionCategory.COMMANDS), IgnoredPhrases.NONE,
                true, 0L));

        assertNull(set.firstMatch("Steve", "cya"), "a farewell answered through the raw set");
        assertNotNull(set.firstMatch("Steve", "!tps"), "a command was refused through the raw set");
    }

    @Test
    @DisplayName("rebuilding the rules does not reopen a switch the user closed")
    void rebuildKeepsTheGate() {
        AssistantRuntime runtime = runtimeWith(
            new ReactionGate(Set.of(ReactionCategory.COMMANDS), IgnoredPhrases.NONE, true, 0L));

        // A rebuild swaps the rule list in. If that also reset the gate, the bug would come back
        // every time any unrelated setting changed.
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));

        assertFalse(runtime.handle("Steve", "cya", NOW).acts(),
            "a rebuild reopened the farewell category");
        assertTrue(runtime.handle("Steve", "!tps", NOW).acts());
    }

    @Test
    @DisplayName("an empty category set means silence, not everything")
    void emptyCategorySetIsSilent() {
        ReactionGate gate = new ReactionGate(Set.of(), IgnoredPhrases.NONE, true, 0L);
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertFalse(gate.check(rule, "Steve", rule.pattern(), NOW).allowed(),
                "'" + rule.pattern() + "' answered with no categories enabled");
        }
    }
}
