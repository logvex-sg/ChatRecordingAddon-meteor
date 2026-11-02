package com.logvex.addon.chatrelay;

import com.logvex.addon.chatrelay.core.ChatBuffer;
import com.logvex.addon.chatrelay.core.ChatMessage;
import com.logvex.addon.chatrelay.core.ChatReport;
import com.logvex.addon.chatrelay.core.ReportPayloadBuilder;
import com.logvex.addon.chatrelay.core.ReportMetadata;
import com.logvex.addon.chatrelay.core.ArchiveCodec;
import com.logvex.addon.chatrelay.core.ArchiveCorruptedException;
import com.logvex.addon.chatrelay.core.Json;
import com.logvex.addon.chatrelay.core.ReportArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRelayCoreTest {

    // ---------------------------------------------------------------- buffer

    @Test
    @DisplayName("the buffer drains without losing messages and keeps capacity")
    void bufferBasics() {
        ChatBuffer buffer = new ChatBuffer(3);
        buffer.add(msg("a"));
        buffer.add(msg("b"));
        assertEquals(2, buffer.size());
        assertEquals(2, buffer.peek().size(), "peek does not consume");

        List<ChatMessage> drained = buffer.drain();
        assertEquals(2, drained.size());
        assertTrue(buffer.isEmpty(), "drain empties the buffer");
    }

    @Test
    @DisplayName("the buffer drops the oldest messages when it is full")
    void bufferCapacity() {
        ChatBuffer buffer = new ChatBuffer(2);
        buffer.add(msg("a"));
        buffer.add(msg("b"));
        buffer.add(msg("c"));
        List<ChatMessage> kept = buffer.drain();
        assertEquals(2, kept.size());
        assertEquals("b", kept.get(0).content());
        assertEquals("c", kept.get(1).content());
    }

    @Test
    @DisplayName("concurrent producers do not lose messages")
    void bufferConcurrency() throws Exception {
        ChatBuffer buffer = new ChatBuffer(100_000);
        int threads = 4;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) buffer.add(msg("m" + i));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads * perThread, buffer.size());
    }

    // ----------------------------------------------------------------- codec

    @Test
    @DisplayName("gzip codec round-trips UTF-8 messages")
    void codecRoundTrip() throws Exception {
        List<ChatMessage> messages = List.of(
            new ChatMessage(1L, ChatMessage.Kind.CHAT, "Alice", "hello"),
            new ChatMessage(2L, ChatMessage.Kind.GAME, "", "サーバーに参加しました"),
            new ChatMessage(3L, ChatMessage.Kind.ACTION_BAR, "Bob", "emoji 🎉 and \"quotes\"")
        );

        byte[] encoded = ArchiveCodec.encode(messages, 6);
        List<ChatMessage> decoded = ArchiveCodec.decode(encoded);

        assertEquals(messages.size(), decoded.size());
        for (int i = 0; i < messages.size(); i++) {
            assertEquals(messages.get(i).content(), decoded.get(i).content());
            assertEquals(messages.get(i).sender(), decoded.get(i).sender());
            assertEquals(messages.get(i).kind(), decoded.get(i).kind());
            assertEquals(messages.get(i).timestamp(), decoded.get(i).timestamp());
        }
    }

    @Test
    @DisplayName("compression actually shrinks repetitive chat")
    void codecCompresses() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 500; i++) messages.add(msg("the same repeated line of chat"));

        byte[] encoded = ArchiveCodec.encode(messages, 9);
        long raw = 0;
        for (ChatMessage m : messages) raw += m.content().getBytes(StandardCharsets.UTF_8).length;

        assertTrue(encoded.length < raw, "compressed " + encoded.length + " < raw " + raw);
    }

    @Test
    @DisplayName("a corrupted payload is detected instead of silently decoded")
    void codecDetectsCorruption() throws Exception {
        List<ChatMessage> messages = List.of(msg("important"));
        byte[] encoded = ArchiveCodec.encode(messages, 6);

        // Flip a byte inside the compressed body.
        byte[] corrupted = encoded.clone();
        corrupted[corrupted.length - 5] ^= 0x5A;

        assertThrows(ArchiveCorruptedException.class, () -> ArchiveCodec.decode(corrupted));
    }

    @Test
    @DisplayName("a truncated entry is rejected")
    void codecDetectsTruncation() throws Exception {
        byte[] encoded = ArchiveCodec.encode(List.of(msg("x")), 6);
        byte[] truncated = new byte[encoded.length - 3];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertThrows(ArchiveCorruptedException.class, () -> ArchiveCodec.decode(truncated));
    }

    // --------------------------------------------------------------- archive

    @Test
    @DisplayName("reports survive a restart and load lazily")
    void archivePersistsAcrossRestart(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();

        ChatReport report = report("r1", "play.example.net", "hello", "world");
        ReportMetadata written = archive.write(report, 6);
        assertTrue(written.compressedSize() > 0);

        // A brand new instance simulates a restart.
        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertEquals(1, reopened.size());

        ChatReport loaded = reopened.load("r1");
        assertEquals(2, loaded.messageCount());
        assertEquals("hello", loaded.messages().get(0).content());
        assertEquals("play.example.net", loaded.serverAddress());
    }

    @Test
    @DisplayName("deleting a report removes its file and index entry")
    void archiveDelete(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "s", "a"), 6);
        archive.write(report("r2", "s", "b"), 6);

        assertTrue(archive.delete("r1"));
        assertEquals(1, archive.size());

        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertEquals(1, reopened.size());
        assertEquals("r2", reopened.all().get(0).id());
    }

    @Test
    @DisplayName("a corrupt archive file is reported instead of returning garbage")
    void archiveCorruptFile(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "s", "a"), 6);

        // Corrupt the stored file.
        Path file = dir.resolve(archive.metadata("r1").fileName());
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 4] ^= 0x11;
        Files.write(file, bytes);

        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertThrows(ArchiveCorruptedException.class, () -> reopened.load("r1"));
    }

    @Test
    @DisplayName("a missing index is rebuilt from the report files")
    void archiveRebuildsIndex(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "server-a", "hello"), 6);

        Files.delete(dir.resolve("index.json"));

        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertEquals(1, reopened.size());
        assertEquals("r1", reopened.all().get(0).id());
    }

    @Test
    @DisplayName("search finds matches inside a compressed report")
    void archiveSearch(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "s", "diamond found here", "cobblestone everywhere"), 6);

        List<ChatMessage> matches = archive.search("r1", "cobblestone");
        assertEquals(1, matches.size());
        assertEquals("cobblestone everywhere", matches.get(0).content());

        assertTrue(archive.search("r1", "nothing").isEmpty());
    }

    @Test
    @DisplayName("cleanup removes the oldest reports first and never deletes everything")
    void archiveCleanup(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();

        long base = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            archive.write(new ChatReport("r" + i, "s", base + i, base + i, base + i,
                ChatReport.STATUS_DELIVERED, List.of(msg("m" + i))), 6);
        }
        assertEquals(5, archive.size());

        // Keep at most 2 reports.
        int removed = archive.cleanup(2, 0, 0, base + 10);
        assertEquals(3, removed);
        assertEquals(2, archive.size());
        assertEquals("r4", archive.all().get(0).id());
        assertEquals("r3", archive.all().get(1).id());
    }

    @Test
    @DisplayName("cleanup by age removes only expired reports")
    void archiveCleanupByAge(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();

        long old = 1_000L;
        long recent = 10_000_000L;
        archive.write(new ChatReport("old", "s", old, old, old, ChatReport.STATUS_LOCAL, List.of(msg("a"))), 6);
        archive.write(new ChatReport("new", "s", recent, recent, recent, ChatReport.STATUS_LOCAL, List.of(msg("b"))), 6);

        int removed = archive.cleanup(0, 0, 5_000L, recent);
        assertEquals(1, removed);
        assertEquals(1, archive.size());
        assertEquals("new", archive.all().get(0).id());
    }

    @Test
    @DisplayName("archive totals track compressed and uncompressed sizes")
    void archiveTotals(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "s", "a".repeat(1000)), 9);

        assertTrue(archive.totalCompressedBytes() > 0);
        assertTrue(archive.totalUncompressedBytes() >= 1000);
        assertTrue(archive.totalCompressedBytes() <= archive.totalUncompressedBytes());
    }

    @Test
    @DisplayName("deleteAll clears the archive")
    void archiveDeleteAll(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        archive.write(report("r1", "s", "a"), 6);
        archive.write(report("r2", "s", "b"), 6);

        assertEquals(2, archive.deleteAll());
        assertEquals(0, archive.size());

        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertEquals(0, reopened.size());
    }

    @Test
    @DisplayName("the delivery status can be updated after a successful send")
    void archiveStatusUpdate(@TempDir Path dir) throws Exception {
        ReportArchive archive = new ReportArchive(dir);
        archive.load();
        ReportMetadata metadata = archive.write(
            report("r1", "s", "a").withDeliveryStatus(ChatReport.STATUS_PENDING), 6);

        archive.updateMetadata(metadata.withStatus(ChatReport.STATUS_DELIVERED));

        ReportArchive reopened = new ReportArchive(dir);
        reopened.load();
        assertEquals(ChatReport.STATUS_DELIVERED, reopened.metadata("r1").deliveryStatus());
    }

    // --------------------------------------------------------------- payload

    @Test
    @DisplayName("the webhook payload never contains the webhook url")
    void payloadHasNoSecret() {
        String payload = ReportPayloadBuilder.discordPayload(report("r1", "s", "hello"), "Relay");
        assertFalse(payload.contains("discord.com/api/webhooks"), "no webhook url in the body");
        assertFalse(payload.contains("http"), "no urls at all");
    }

    @Test
    @DisplayName("the webhook payload disables mentions")
    void payloadDisablesMentions() {
        String payload = ReportPayloadBuilder.discordPayload(report("r1", "s", "@everyone hello"), "Relay");
        assertTrue(payload.contains("\"allowed_mentions\""));
        assertTrue(payload.contains("\"parse\":[]"), "mentions are disabled: " + payload);
    }

    @Test
    @DisplayName("the payload is capped at the Discord content limit")
    void payloadIsCapped() {
        List<ChatMessage> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(msg("a fairly long chat line number " + i));
        ChatReport report = new ChatReport("r1", "s", 1, 2, 3, ChatReport.STATUS_LOCAL, many);

        String content = ReportPayloadBuilder.buildContent(report);
        assertTrue(content.length() <= ReportPayloadBuilder.DISCORD_CONTENT_LIMIT,
            "content length " + content.length());
    }

    @Test
    @DisplayName("json helpers round-trip maps and lists")
    void jsonHelpers() {
        String json = Json.toJson(List.of("a", "b"));
        assertEquals(List.of("a", "b"), Json.toStringList(json));

        assertNotNull(Json.toMap("{\"id\":\"x\"}"));
        assertEquals("x", ReportMetadata.fromMap(Json.toMap("{\"id\":\"x\",\"count\":3}")).id());
        assertEquals(3, ReportMetadata.fromMap(Json.toMap("{\"id\":\"x\",\"count\":3}")).messageCount());
    }

    // --------------------------------------------------------------- helpers

    private static ChatMessage msg(String content) {
        return new ChatMessage(System.currentTimeMillis(), ChatMessage.Kind.CHAT, "tester", content);
    }

    private static ChatReport report(String id, String server, String... contents) {
        List<ChatMessage> messages = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String content : contents) messages.add(new ChatMessage(now, ChatMessage.Kind.CHAT, "tester", content));
        return new ChatReport(id, server, now, now, now, ChatReport.STATUS_LOCAL, messages);
    }
}
