package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.mc.FileAssistantStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On-disk assistant memory: round-trip, corruption recovery and forward-compatibility. */
class FileAssistantStoreTest {
    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("assistant-memory.json");
    }

    @Test
    @DisplayName("memory survives a save and load with every field intact")
    void roundTrips() {
        FileAssistantStore store = new FileAssistantStore(file());
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("home", "the castle", 1_000L);
        memory.rememberPlayerFact("Notch", "rank", "admin", 2_000L);
        memory.recordConversation("Notch", "hello", "hi there", 3_000L);
        memory.incrementCounter("replies", 4);
        store.save(memory);

        Optional<AssistantMemory> loaded = store.load();
        assertTrue(loaded.isPresent());
        AssistantMemory back = loaded.get();
        assertEquals("the castle", back.fact("home").orElseThrow().value());
        assertEquals("admin", back.playerFact("Notch", "rank").orElseThrow().value());
        assertEquals(1, back.historySize());
        assertEquals("hello", back.history(1).get(0).question());
        assertEquals(4, back.counter("replies"));
    }

    @Test
    @DisplayName("a missing file loads as empty rather than an error")
    void missingFileIsEmpty() {
        FileAssistantStore store = new FileAssistantStore(file());
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("a corrupt file is reported and loads as empty, not thrown")
    void corruptFileRecovers() throws IOException {
        Files.writeString(file(), "{ this is not json", StandardCharsets.UTF_8);
        FileAssistantStore store = new FileAssistantStore(file());

        assertTrue(store.load().isEmpty());
        assertFalse(store.lastError().isBlank());
    }

    @Test
    @DisplayName("a file from a newer build is refused rather than downgraded")
    void newerVersionRefused() throws IOException {
        Files.writeString(file(),
            "{\"version\":" + (AssistantMemory.CURRENT_VERSION + 5) + ",\"facts\":{}}",
            StandardCharsets.UTF_8);
        FileAssistantStore store = new FileAssistantStore(file());

        assertTrue(store.load().isEmpty());
        assertFalse(store.lastError().isBlank());
    }

    @Test
    @DisplayName("clear deletes the file and a later load is empty")
    void clear() {
        FileAssistantStore store = new FileAssistantStore(file());
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("a", "b", 1L);
        store.save(memory);
        assertTrue(Files.exists(file()));

        store.clear();
        assertFalse(Files.exists(file()));
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("saving twice leaves no temporary file behind")
    void noTempLeftBehind() throws IOException {
        FileAssistantStore store = new FileAssistantStore(file());
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("a", "b", 1L);
        store.save(memory);
        memory.rememberFact("c", "d", 2L);
        store.save(memory);

        try (var stream = Files.list(directory)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }
}
