package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.CommandDefaults;
import com.logvex.addon.assistant.core.StarterKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter database, memory recall, and the per-command table.
 *
 * <p>These three are what make the assistant useful on a fresh install rather than only after
 * configuration, so each one is asserted against the behaviour a user would notice.
 */
class StarterAndRecallTest {

    // ------------------------------------------------------------------ starter database

    @Test
    @DisplayName("a fresh install ships with facts it can answer from")
    void starterFactsExist() {
        List<StarterKnowledge.Entry> entries = StarterKnowledge.entries();
        assertFalse(entries.isEmpty(), "a fresh install must not start with an empty memory");
        for (StarterKnowledge.Entry entry : entries) {
            assertFalse(entry.key().isBlank(), "every seed fact needs a key");
            assertFalse(entry.value().isBlank(), "a seed fact with no value answers nothing");
            assertEquals(entry.key().toLowerCase(java.util.Locale.ROOT), entry.key(),
                "keys are lower case so recall can match them");
        }
    }

    @Test
    @DisplayName("the seed covers the questions a new user actually asks")
    void starterCoversTheBasics() {
        Map<String, String> facts = StarterKnowledge.asMap();
        for (String key : List.of("assistant", "prefix", "help", "memory", "status", "macros")) {
            assertTrue(facts.containsKey(key), "a new user will ask about " + key);
        }
    }

    // ------------------------------------------------------------------ recall

    @Test
    @DisplayName("a question naming a stored fact recalls it")
    void recallFindsTheFact() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("prefix", "! by default", 1L);

        assertTrue(memory.recall("what is the prefix?").isPresent());
        assertEquals("prefix", memory.recall("what is the prefix?").orElseThrow().key());
    }

    @Test
    @DisplayName("recall matches whole words, so an unrelated word is not a hit")
    void recallNeedsWholeWords() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("fact", "a stored fact", 1L);

        assertTrue(memory.recall("what is a fact").isPresent());
        assertTrue(memory.recall("tell me about the factory").isEmpty(),
            "factory contains fact but is not the key");
    }

    @Test
    @DisplayName("two matching keys is ambiguous, and ambiguity answers nothing")
    void recallRefusesAmbiguity() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("server", "one", 1L);
        memory.rememberFact("spawn", "two", 1L);

        assertTrue(memory.recall("where is the server spawn").isEmpty(),
            "guessing between two facts is worse than not answering");
    }

    @Test
    @DisplayName("the longer key wins when one key contains another")
    void recallPrefersTheSpecificKey() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("spawn", "general", 1L);
        memory.rememberFact("spawn point", "specific", 1L);

        assertEquals("spawn point",
            memory.recall("what is the spawn point").orElseThrow().key());
    }

    @Test
    @DisplayName("an empty memory recalls nothing and does not throw")
    void recallOnEmptyMemory() {
        assertTrue(new AssistantMemory().recall("what is the prefix").isEmpty());
        assertTrue(new AssistantMemory().recall(null).isEmpty());
        assertTrue(new AssistantMemory().recall("").isEmpty());
    }

    // ------------------------------------------------------------------ command table

    @Test
    @DisplayName("every prefix command ships with a default entry")
    void everyCommandHasAnEntry() {
        Map<String, CommandDefaults.Entry> defaults = CommandDefaults.defaults();
        for (com.logvex.addon.assistant.core.CommandCatalog.Command command
                : com.logvex.addon.assistant.core.CommandCatalog.all()) {
            assertTrue(defaults.containsKey(command.name()),
                command.name() + " has no shipped configuration");
        }
    }

    @Test
    @DisplayName("a command line round-trips through the stored form")
    void entryRoundTrips() {
        CommandDefaults.Entry entry =
            new CommandDefaults.Entry("who", false, CommandDefaults.Output.CHAT, "Players: {playercount}");
        String encoded = entry.encode();
        CommandDefaults.Entry decoded = CommandDefaults.decode(encoded).orElseThrow();

        assertEquals(entry.name(), decoded.name());
        assertEquals(entry.enabled(), decoded.enabled());
        assertEquals(entry.output(), decoded.output());
        assertEquals(entry.template(), decoded.template());
    }

    @Test
    @DisplayName("a short line means the obvious thing")
    void shortLineIsUnderstood() {
        CommandDefaults.Entry off = CommandDefaults.decode("who = off").orElseThrow();
        assertFalse(off.enabled());
        assertFalse(off.customised(), "switching a command off must not invent a template");

        CommandDefaults.Entry chat = CommandDefaults.decode("gg = on | chat").orElseThrow();
        assertTrue(chat.enabled());
        assertEquals(CommandDefaults.Output.CHAT, chat.output());
        assertFalse(chat.customised());
    }

    @Test
    @DisplayName("a template with a pipe in it is not truncated")
    void templateKeepsItsPipes() {
        CommandDefaults.Entry entry =
            CommandDefaults.decode("stats = on | local | TPS {tps} | {playercount} online")
                .orElseThrow();
        assertEquals("TPS {tps} | {playercount} online", entry.template());
    }

    @Test
    @DisplayName("a malformed line is skipped, not allowed to break the table")
    void malformedLineIsSkipped() {
        assertTrue(CommandDefaults.decode("   ").isEmpty());
        assertTrue(CommandDefaults.decode("# a comment").isEmpty());
        assertTrue(CommandDefaults.decode("two words = on").isEmpty());
    }

    @Test
    @DisplayName("a command added later arrives enabled even if the saved table predates it")
    void missingCommandsAreFilledIn() {
        Map<String, CommandDefaults.Entry> parsed =
            CommandDefaults.parseAll(List.of("who = off"));
        assertFalse(parsed.get("who").enabled(), "the saved line wins");
        assertTrue(parsed.containsKey("hello"), "an unmentioned command still ships");
        assertTrue(parsed.get("hello").enabled());
    }
}
