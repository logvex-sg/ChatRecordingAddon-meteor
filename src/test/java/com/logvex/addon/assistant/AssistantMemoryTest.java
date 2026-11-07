package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Memory: facts, per-player facts, bounded history, counters and version migration. */
class AssistantMemoryTest {

    @Test
    @DisplayName("a fact is stored, replaced and forgotten by its normalised key")
    void factsRoundTrip() {
        AssistantMemory memory = new AssistantMemory();
        assertTrue(memory.rememberFact("Home", "the castle", 100L));
        assertEquals("the castle", memory.fact("home").orElseThrow().value());

        memory.rememberFact("home", "the village", 200L);
        assertEquals("the village", memory.fact("HOME").orElseThrow().value());
        assertEquals(1, memory.facts().size());

        assertTrue(memory.forgetFact("home"));
        assertTrue(memory.fact("home").isEmpty());
    }

    @Test
    @DisplayName("a blank fact name is rejected")
    void blankFactKeyRejected() {
        AssistantMemory memory = new AssistantMemory();
        assertFalse(memory.rememberFact("", "x", 0L));
        assertFalse(memory.rememberFact("   ", "x", 0L));
        assertFalse(memory.rememberFact(null, "x", 0L));
    }

    @Test
    @DisplayName("per-player facts are keyed by player and forget-me clears them")
    void playerFacts() {
        AssistantMemory memory = new AssistantMemory();
        assertTrue(memory.rememberPlayerFact("Notch", "rank", "admin", 1L));
        assertEquals("admin", memory.playerFact("notch", "rank").orElseThrow().value());
        assertEquals(1, memory.playerFacts("Notch").size());
        assertEquals(1, memory.knownPlayers().size());

        assertTrue(memory.forgetPlayer("Notch"));
        assertTrue(memory.playerFacts("Notch").isEmpty());
        assertTrue(memory.knownPlayers().isEmpty());
    }

    @Test
    @DisplayName("history keeps only the most recent entries")
    void historyIsBounded() {
        AssistantMemory memory = new AssistantMemory();
        for (int i = 0; i < AssistantMemory.MAX_HISTORY + 25; i++) {
            memory.recordConversation("p", "q" + i, "a" + i, i);
        }
        assertEquals(AssistantMemory.MAX_HISTORY, memory.historySize());
        // The newest entry is the last recorded one.
        assertEquals("q" + (AssistantMemory.MAX_HISTORY + 24), memory.history(1).get(0).question());
    }

    @Test
    @DisplayName("history returns newest first")
    void historyOrder() {
        AssistantMemory memory = new AssistantMemory();
        memory.recordConversation("p", "first", "a", 1L);
        memory.recordConversation("p", "second", "b", 2L);
        assertEquals("second", memory.history(2).get(0).question());
        assertEquals("first", memory.history(2).get(1).question());
    }

    @Test
    @DisplayName("counters accumulate by normalised name")
    void counters() {
        AssistantMemory memory = new AssistantMemory();
        memory.incrementCounter("Replies", 2);
        memory.incrementCounter("replies", 3);
        assertEquals(5, memory.counter("REPLIES"));
        assertEquals(0, memory.counter("missing"));
    }

    @Test
    @DisplayName("an empty memory migrates and a newer one is refused")
    void migration() {
        AssistantMemory memory = new AssistantMemory();
        assertTrue(memory.migrate(Map.of()));
        assertEquals(AssistantMemory.CURRENT_VERSION, memory.version());

        AssistantMemory older = new AssistantMemory();
        assertTrue(older.migrate(Map.of("version", 0)));
        assertEquals(AssistantMemory.CURRENT_VERSION, older.version());

        AssistantMemory newer = new AssistantMemory();
        assertFalse(newer.migrate(Map.of("version", AssistantMemory.CURRENT_VERSION + 1)));
    }

    @Test
    @DisplayName("clear empties every collection")
    void clear() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("a", "b", 1L);
        memory.rememberPlayerFact("p", "k", "v", 1L);
        memory.recordConversation("p", "q", "a", 1L);
        memory.incrementCounter("c", 1);
        memory.clear();
        assertTrue(memory.isEmpty());
    }

    @Test
    @DisplayName("copy is a deep, independent snapshot")
    void copyIsIndependent() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("a", "b", 1L);
        memory.rememberPlayerFact("p", "k", "v", 1L);
        memory.recordConversation("p", "q", "a", 1L);
        memory.incrementCounter("c", 2);

        AssistantMemory copy = memory.copy();
        memory.rememberFact("a", "changed", 2L);
        memory.rememberPlayerFact("p", "k", "changed", 2L);
        memory.recordConversation("p", "q2", "a2", 2L);
        memory.incrementCounter("c", 3);

        assertEquals("b", copy.fact("a").orElseThrow().value());
        assertEquals("v", copy.playerFact("p", "k").orElseThrow().value());
        assertEquals(1, copy.historySize());
        assertEquals(2, copy.counter("c"));
    }
}
