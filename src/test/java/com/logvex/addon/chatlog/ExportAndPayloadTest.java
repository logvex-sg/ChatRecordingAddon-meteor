package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.Json;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.WebhookPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportAndPayloadTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "mc.example.com", "s1");
    }

    @Test
    void exportsTxtJsonAndCsv(@TempDir Path dir) throws IOException {
        List<ChatEntry> entries = List.of(
            entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello, world"),
            entry(2000, MessageType.JOIN, "Alex", "Alex joined the game")
        );

        for (LogExporter.Format format : LogExporter.Format.values()) {
            Path target = dir.resolve("out." + format.extension());
            LogExporter exporter = new LogExporter(format);
            exporter.open(target);
            exporter.writeAll(entries);
            exporter.close();

            assertTrue(Files.exists(target), format + " file should exist");
            String text = Files.readString(target, StandardCharsets.UTF_8);
            assertFalse(text.isBlank(), format + " file should not be empty");
            assertEquals(2, exporter.written());
            assertFalse(Files.exists(dir.resolve("out." + format.extension() + ".part")),
                "the temporary file should have been moved");
        }
    }

    @Test
    void abortedExportLeavesNoFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("aborted.txt");
        LogExporter exporter = new LogExporter(LogExporter.Format.TXT);
        exporter.open(target);
        exporter.write(entry(1000, MessageType.PUBLIC_CHAT, "Notch", "partial"));
        exporter.abort();

        assertFalse(Files.exists(target));
    }

    @Test
    void queryMatchesTextPlayerAndType() {
        ChatEntry entry = entry(1000, MessageType.PUBLIC_CHAT, "Notch", "Selling diamonds cheap");
        assertTrue(LogQuery.builder().text("diamond").build().matches(entry));
        assertTrue(LogQuery.builder().player("notch").build().matches(entry));
        assertTrue(LogQuery.builder().types(java.util.EnumSet.of(MessageType.PUBLIC_CHAT)).build().matches(entry));
        assertFalse(LogQuery.builder().text("emerald").build().matches(entry));
        assertFalse(LogQuery.builder().types(java.util.EnumSet.of(MessageType.DEATH)).build().matches(entry));
    }

    @Test
    void queryHonoursTimeWindow() {
        ChatEntry entry = entry(5000, MessageType.PUBLIC_CHAT, "Notch", "hi");
        assertTrue(LogQuery.builder().from(1000).to(9000).build().matches(entry));
        assertFalse(LogQuery.builder().from(6000).build().matches(entry));
        assertFalse(LogQuery.builder().to(4000).build().matches(entry));
    }

    @Test
    void alertPayloadIsValidJsonAndCarriesNoUrl() {
        KeywordRule rule = KeywordRule.of("wts");
        ChatEntry entry = entry(1000, MessageType.PUBLIC_CHAT, "Notch", "WTS diamonds");

        String payload = WebhookPayload.alert("Log LogClientContext", rule, entry);
        Map<?, ?> map = Json.toMap(payload);

        assertNotNull(map);
        assertEquals("Log LogClientContext", map.get("username"));
        assertTrue(String.valueOf(map.get("content")).contains("wts"));
        assertFalse(payload.contains("http"), "the payload must never contain a webhook URL");
    }

    @Test
    void alertPayloadEscapesDiscordMentions() {
        KeywordRule rule = KeywordRule.of("@everyone");
        ChatEntry entry = entry(1000, MessageType.PUBLIC_CHAT, "Notch", "@everyone come here");

        Map<?, ?> map = Json.toMap(WebhookPayload.alert("Log LogClientContext", rule, entry));
        assertNotNull(map);
        Map<?, ?> mentions = (Map<?, ?>) map.get("allowed_mentions");
        assertNotNull(mentions, "mentions must be explicitly restricted");
        assertTrue(((List<?>) mentions.get("parse")).isEmpty());
    }

    @Test
    void summaryPayloadTruncatesToDiscordLimit() {
        List<ChatEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            entries.add(entry(1000 + i, MessageType.PUBLIC_CHAT, "Notch", "a reasonably long chat message body " + i));
        }

        Map<?, ?> map = Json.toMap(WebhookPayload.summary("Log LogClientContext", "mc.example.com", entries));
        assertNotNull(map);
        assertTrue(String.valueOf(map.get("content")).length() <= WebhookPayload.CONTENT_LIMIT);
    }

    @Test
    void testPayloadIsValidJson() {
        Map<?, ?> map = Json.toMap(WebhookPayload.test("Log LogClientContext"));
        assertNotNull(map);
        assertEquals("Log LogClientContext", map.get("username"));
    }

    @Test
    void entryMapRoundTripPreservesFields() {
        ChatEntry original = entry(1234, MessageType.DEATH, "Notch", "Notch was slain");
        ChatEntry decoded = ChatEntry.fromMap(original.toMap());
        assertEquals(original, decoded);
    }
}
