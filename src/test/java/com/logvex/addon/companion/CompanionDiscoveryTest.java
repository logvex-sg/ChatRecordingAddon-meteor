package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionDiscovery;
import com.logvex.addon.companion.core.CompanionJson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file that lets a local dashboard connect without being told a URL.
 *
 * <p>It carries a secret, so the properties that matter are that it round-trips, that it disappears
 * on a clean stop, and that a failed write is reported rather than thrown into a game tick.
 */
class CompanionDiscoveryTest {

    @Test
    @DisplayName("publishing writes a readable document with the live address")
    void publishesReadableDocument(@TempDir Path dir) throws Exception {
        CompanionDiscovery discovery = new CompanionDiscovery(dir.resolve("companion.json"));

        assertTrue(discovery.publish("127.0.0.1", 6767, "s3cret"));
        assertTrue(discovery.exists());
        assertEquals("", discovery.lastError());

        JsonObject doc = CompanionJson.parseObject(
            Files.readString(discovery.file(), StandardCharsets.UTF_8));
        assertNotNull(doc, "the discovery file must be a JSON object");
        assertEquals(CompanionDiscovery.VERSION, doc.get("version").getAsInt());
        assertEquals("127.0.0.1", doc.get("host").getAsString());
        assertEquals(6767, doc.get("port").getAsInt());
        assertEquals("s3cret", doc.get("token").getAsString());
        assertTrue(doc.get("pid").getAsLong() > 0, "the reader needs a pid to spot a stale file");
    }

    @Test
    @DisplayName("two clients on one machine do not overwrite each other's discovery file")
    void fileNameIsUniquePerPort() {
        // Both instances share the addon data folder, so a single fixed name meant the second
        // companion silently replaced the first's token and port.
        assertEquals(CompanionDiscovery.FILE_NAME, CompanionDiscovery.fileNameFor(6767),
            "the default port keeps the plain name, so an existing setup is unchanged");
        assertNotEquals(CompanionDiscovery.fileNameFor(6767),
            CompanionDiscovery.fileNameFor(6768));
        assertTrue(CompanionDiscovery.fileNameFor(6768).contains("6768"),
            "the file name must say which port it belongs to");
    }

    @Test
    @DisplayName("withdrawing removes the file and is safe to call twice")
    void withdrawIsIdempotent(@TempDir Path dir) {
        CompanionDiscovery discovery = new CompanionDiscovery(dir.resolve("companion.json"));
        discovery.publish("127.0.0.1", 6767, "t");

        assertTrue(discovery.withdraw());
        assertFalse(discovery.exists());
        assertFalse(discovery.withdraw(), "a second withdraw has nothing to remove");
        assertEquals("", discovery.lastError());
    }

    @Test
    @DisplayName("a publish with no bound port is refused instead of writing a dead address")
    void refusesUnboundServer(@TempDir Path dir) {
        CompanionDiscovery discovery = new CompanionDiscovery(dir.resolve("companion.json"));

        assertFalse(discovery.publish("127.0.0.1", 0, "t"));
        assertFalse(discovery.exists());
        assertFalse(discovery.lastError().isBlank(), "a refusal must say why");
    }

    @Test
    @DisplayName("a failed write is reported, not thrown")
    void failureIsReportedNotThrown(@TempDir Path dir) throws Exception {
        // A directory where the file should be makes the move fail on every platform.
        Path target = dir.resolve("companion.json");
        Files.createDirectories(target);
        CompanionDiscovery discovery = new CompanionDiscovery(target);

        assertFalse(discovery.publish("127.0.0.1", 6767, "t"));
        assertFalse(discovery.lastError().isBlank());
    }

    @Test
    @DisplayName("the URL helpers keep the token out of the path")
    void urlHelpers() {
        assertEquals("http://127.0.0.1:6767",
            CompanionDiscovery.baseUrl("127.0.0.1", 6767));
        assertEquals("http://127.0.0.1:6767/?token=abc",
            CompanionDiscovery.urlWithToken("127.0.0.1", 6767, "abc"));
        assertEquals("http://127.0.0.1:6767",
            CompanionDiscovery.urlWithToken("127.0.0.1", 6767, ""));
    }

    @Test
    @DisplayName("republishing replaces the previous address")
    void republishReplaces(@TempDir Path dir) throws Exception {
        CompanionDiscovery discovery = new CompanionDiscovery(dir.resolve("companion.json"));
        discovery.publish("127.0.0.1", 6767, "old");
        discovery.publish("127.0.0.1", 7000, "new");

        JsonObject doc = CompanionJson.parseObject(
            Files.readString(discovery.file(), StandardCharsets.UTF_8));
        assertEquals(7000, doc.get("port").getAsInt());
        assertEquals("new", doc.get("token").getAsString());
    }
}
