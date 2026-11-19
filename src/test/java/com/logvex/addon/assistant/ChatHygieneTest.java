package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.ChatActivity;
import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.DuplicateSuppressor;
import com.logvex.addon.assistant.core.GreetingPools;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat-hygiene features: duplicate suppression, reply pools, aliases and activity stats. */
class ChatHygieneTest {

    // ------------------------------------------------------------------ duplicate suppression

    @Test
    @DisplayName("a repeated line inside the window is suppressed")
    void repeatInsideWindowIsSuppressed() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertTrue(suppressor.accept("Player", "hello", 1_000L));
        assertFalse(suppressor.accept("Player", "hello", 2_000L));
    }

    @Test
    @DisplayName("the same line from a different player is not a duplicate")
    void sameLineFromAnotherPlayerIsNotADuplicate() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertTrue(suppressor.accept("Alice", "hello", 1_000L));
        assertTrue(suppressor.accept("Bob", "hello", 1_100L));
    }

    @Test
    @DisplayName("the same line is allowed again once the window has passed")
    void repeatAfterWindowIsAllowed() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertTrue(suppressor.accept("Player", "hello", 1_000L));
        assertTrue(suppressor.accept("Player", "hello", 6_000L));
    }

    @Test
    @DisplayName("case and surrounding spaces do not make a line new")
    void caseAndWhitespaceDoNotDefeatTheFilter() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertTrue(suppressor.accept("Player", "Hello There", 1_000L));
        assertFalse(suppressor.accept("Player", "  hello there  ", 1_500L));
        assertFalse(suppressor.accept("Player", "HELLO THERE", 2_000L));
    }

    @Test
    @DisplayName("a different line from the same player still gets through")
    void differentLineIsNotSuppressed() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertTrue(suppressor.accept("Player", "ping", 1_000L));
        assertTrue(suppressor.accept("Player", "tps", 1_100L));
    }

    @Test
    @DisplayName("a blank line is never accepted, so it can never be answered")
    void blankIsNeverAccepted() {
        DuplicateSuppressor suppressor = new DuplicateSuppressor(4_000L);

        assertFalse(suppressor.accept("Player", "", 1_000L));
        assertFalse(suppressor.accept("Player", "   ", 1_100L));
        assertFalse(suppressor.accept("Player", null, 1_200L));
    }

    @Test
    @DisplayName("suppression is scoped to the assistant, so it cannot be starved by other traffic")
    void suppressionIsScopedPerAssistant() {
        DuplicateSuppressor first = new DuplicateSuppressor(4_000L);
        DuplicateSuppressor second = new DuplicateSuppressor(4_000L);

        assertTrue(first.accept("Player", "hello", 1_000L));
        assertTrue(second.accept("Player", "hello", 1_000L),
            "a second assistant instance has its own window");
    }

    @Test
    @DisplayName("the runtime drops a duplicate and records it")
    void runtimeDropsDuplicate() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(List.of(new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true))));
        runtime.setConfig(AssistantConfig.DEFAULTS);

        assertTrue(runtime.handle("Player", "ping", 1_000L).acts());
        assertFalse(runtime.handle("Player", "ping", 1_500L).acts());
        assertEquals(1, runtime.activity().suppressed());
    }

    // ------------------------------------------------------------------ reply pools and aliases

    @Test
    @DisplayName("a rule with several replies answers with one of them")
    void ruleWithPoolAnswersFromThePool() {
        TriggerRule rule = new TriggerRule("hello", false, false, "", "Hi|Hey|Yo",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true);

        for (int i = 0; i < 20; i++) {
            String chosen = rule.chosenReply(null);
            assertTrue(List.of("Hi", "Hey", "Yo").contains(chosen), "unexpected reply: " + chosen);
        }
    }

    @Test
    @DisplayName("a rule with one reply always answers with it")
    void singleReplyIsStable() {
        TriggerRule rule = new TriggerRule("hello", false, false, "", "Hi",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true);

        assertEquals("Hi", rule.chosenReply(null));
    }

    @Test
    @DisplayName("an alias fires the rule just like the main pattern")
    void aliasFiresTheRule() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true,
            "latency,ms", "");

        assertTrue(TriggerSet.matches(rule, "ping"));
        assertTrue(TriggerSet.matches(rule, "latency"));
        assertTrue(TriggerSet.matches(rule, "ms"));
        assertFalse(TriggerSet.matches(rule, "pong"));
    }

    @Test
    @DisplayName("a rule without aliases keeps matching exactly as before")
    void noAliasesMatchesOnlyThePattern() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true);

        assertTrue(TriggerSet.matches(rule, "ping"));
        assertFalse(TriggerSet.matches(rule, "latency"));
    }

    @Test
    @DisplayName("a trigger set finds a rule through its alias")
    void triggerSetFindsAlias() {
        TriggerRule rule = new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true,
            "latency", "");
        TriggerSet set = TriggerSet.of(List.of(rule));

        assertNotNull(set.firstMatch("Player", "latency"));
        assertNull(set.firstMatch("Player", "nothing"));
    }

    @Test
    @DisplayName("the catalog's aliases all resolve to their own command")
    void everyAliasResolvesToItsCommand() {
        for (CommandCatalog.Command command : CommandCatalog.all()) {
            for (String alias : command.aliases()) {
                assertEquals(command.name(), CommandCatalog.canonical(alias).orElse(null),
                    "alias '" + alias + "' must resolve to " + command.name());
                assertFalse(alias.equalsIgnoreCase(command.name()),
                    "a command should not list itself as an alias: " + command.name());
            }
        }
    }

    // ------------------------------------------------------------------ greetings

    @Test
    @DisplayName("each part of the day picks its own pool")
    void greetingPicksTheBucketForTheTime() {
        Map<String, String> pools = Map.of(
            "morning", "Good morning",
            "afternoon", "Good afternoon",
            "evening", "Good evening",
            "night", "Good night");

        assertEquals("Good morning", GreetingPools.greeting(pools, LocalTime.of(6, 0), null));
        assertEquals("Good afternoon", GreetingPools.greeting(pools, LocalTime.of(13, 0), null));
        assertEquals("Good evening", GreetingPools.greeting(pools, LocalTime.of(19, 0), null));
        assertEquals("Good night", GreetingPools.greeting(pools, LocalTime.of(2, 0), null));
    }

    @Test
    @DisplayName("the bucket boundaries are where the settings say they are")
    void greetingBucketsHaveKnownBoundaries() {
        assertEquals("morning", GreetingPools.bucketIdFor(5));
        assertEquals("morning", GreetingPools.bucketIdFor(11));
        assertEquals("afternoon", GreetingPools.bucketIdFor(12));
        assertEquals("afternoon", GreetingPools.bucketIdFor(16));
        assertEquals("evening", GreetingPools.bucketIdFor(17));
        assertEquals("evening", GreetingPools.bucketIdFor(21));
        assertEquals("night", GreetingPools.bucketIdFor(22));
        assertEquals("night", GreetingPools.bucketIdFor(4));
        assertEquals("night", GreetingPools.bucketIdFor(0));
    }

    @Test
    @DisplayName("an empty pool falls back to the built-in greeting, never to a blank reply")
    void emptyPoolFallsBack() {
        String greeting = GreetingPools.greeting(Map.of("morning", ""), LocalTime.of(6, 0), null);

        assertNotNull(greeting);
        assertFalse(greeting.isBlank(), "a greeting must never be empty");
    }

    @Test
    @DisplayName("a null pool map falls back to the built-in greeting")
    void nullPoolFallsBack() {
        assertFalse(GreetingPools.greeting(null, LocalTime.of(6, 0), null).isBlank());
    }

    @Test
    @DisplayName("the greeting is expanded, so it can name the player")
    void greetingExpandsPlaceholders() {
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        AssistantBrain.Decision decision = brain.greeting(
            SenderIdentity.local("Steve", null),
            Map.of("morning", "Good morning, {player}"),
            AssistantConfig.DEFAULTS, LocalTime.of(6, 0), 1_000L);

        assertTrue(decision.acts());
        assertTrue(decision.text().contains("Steve"), "got: " + decision.text());
    }

    // ------------------------------------------------------------------ activity stats

    @Test
    @DisplayName("activity counts lines, players and replies")
    void activityCountsLinesAndPlayers() {
        ChatActivity activity = new ChatActivity();
        activity.observe("Alice", 1_000L);
        activity.observe("Alice", 2_000L);
        activity.observe("Bob", 3_000L);
        activity.recordReply();

        assertEquals(3, activity.seen());
        assertEquals(2, activity.activePlayers());
        assertEquals(1, activity.answered());
        assertEquals(2, activity.mostActive().messages());
    }

    @Test
    @DisplayName("a blank sender is not counted as a player")
    void blankSenderIsNotAPlayer() {
        ChatActivity activity = new ChatActivity();
        activity.observe("", 1_000L);
        activity.observe(null, 1_000L);

        assertEquals(0, activity.activePlayers());
    }

    @Test
    @DisplayName("command counts are case-insensitive")
    void commandCountsIgnoreCase() {
        ChatActivity activity = new ChatActivity();
        activity.recordCommand("Ping");
        activity.recordCommand("ping");

        assertEquals(2, activity.commandCount("PING"));
    }

    @Test
    @DisplayName("the activity summary names the busiest player")
    void activitySummaryNamesBusiestPlayer() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        runtime.handle("Alice", "one", 1_000L);
        runtime.handle("Alice", "two", 2_000L);
        runtime.handle("Bob", "three", 3_000L);

        ChatActivity.PlayerCount busiest = runtime.activity().mostActive();
        assertNotNull(busiest);
        assertEquals("Alice", busiest.name());
        assertEquals(2, busiest.messages());
    }

    @Test
    @DisplayName("resetting the session clears activity but keeps memory")
    void resetClearsActivityButKeepsMemory() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        runtime.handle(SenderIdentity.local("Me", null), "!remember a = b", 1_000L, null, null);
        assertEquals(1, runtime.memory().facts().size());

        runtime.resetSession();
        assertEquals(0, runtime.activity().seen());
        assertEquals(1, runtime.memory().facts().size(), "a session reset must not erase memory");
    }

    // ------------------------------------------------------------------ help and status

    @Test
    @DisplayName("help lists every catalogued command")
    void helpListsEveryCommand() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        AssistantBrain.Decision decision = runtime.handle("Player", "!help", 1_000L);
        assertTrue(decision.acts());
        // Help is paged, so every page is checked rather than only the first one.
        StringBuilder all = new StringBuilder();
        for (int page = 1; page <= 20; page++) {
            AssistantBrain.Decision paged = runtime.handle("Player", "!help " + page,
                1_000L + page * 100L);
            if (!paged.acts()) break;
            all.append(paged.text()).append('\n');
        }
        for (String name : CommandCatalog.names()) {
            assertTrue(all.toString().contains(name),
                "help should mention " + name + ", got: " + all);
        }
    }

    @Test
    @DisplayName("status reports the memory size")
    void statusReportsMemorySize() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        runtime.handle(SenderIdentity.local("Me", null), "!remember a = b", 1_000L, null, null);
        AssistantBrain.Decision decision = runtime.handle("Player", "!status", 2_000L);

        assertTrue(decision.acts());
        // Status is a labelled block now: the fact count appears as a field rather than a phrase.
        assertTrue(decision.text().contains("Facts 1"), "got: " + decision.text());
    }

    @Test
    @DisplayName("a trigger is not fired by a bare prefix")
    void barePrefixDoesNotFireATrigger() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(List.of(new TriggerRule("ping", false, false, "", "pong",
            TriggerRule.ReplyMode.CHAT, 0L, 0, false, false, true))));
        runtime.setConfig(AssistantConfig.DEFAULTS);

        assertFalse(runtime.handle("Player", "!", 1_000L).acts());
        assertFalse(runtime.handle("Player", "   ", 1_000L).acts());
    }

    // ------------------------------------------------------------------ stats aliases

    @Test
    @DisplayName("a catalogued stats alias answers even though it is not the configured verb")
    void statsAliasAnswersThroughCanonicalName() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        com.logvex.addon.stats.ServerStats stats = com.logvex.addon.stats.ServerStats.OFFLINE;

        // `latency` is an alias of `ping`; the stats table itself only knows the verb "ping".
        AssistantBrain.Decision viaAlias =
            runtime.handle("Player", "!latency", 1_000L, stats, null);
        AssistantBrain.Decision viaCanonical =
            runtime.handle("Player", "!ping", 2_000L, stats, null);

        assertTrue(viaAlias.acts(), "an alias must answer, not be silently dropped");
        assertEquals(viaCanonical.text(), viaAlias.text(),
            "an alias must produce the same answer as its canonical command");
    }

    @Test
    @DisplayName("a renamed stats verb still answers on its configured name")
    void renamedStatsVerbAnswers() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        com.logvex.addon.stats.StatsCommands.Options options =
            new com.logvex.addon.stats.StatsCommands.Options(
                true, "latency", "tps", "stats", "coords", "health", "fps", "server", "time",
                com.logvex.addon.stats.ServerStats.Detail.BRIEF, " | ");

        AssistantBrain.Decision renamed = runtime.handle("Player", "!latency", 1_000L,
            com.logvex.addon.stats.ServerStats.OFFLINE, options);
        AssistantBrain.Decision defaultVerb = runtime.handle("Player", "!ping", 2_000L,
            com.logvex.addon.stats.ServerStats.OFFLINE,
            com.logvex.addon.stats.StatsCommands.Options.DEFAULTS);

        assertTrue(renamed.acts(), "the configured verb must be answered");
        // The figures are the same either way; only the verb the player types has changed.
        assertEquals(defaultVerb.text(), renamed.text());
    }
}
