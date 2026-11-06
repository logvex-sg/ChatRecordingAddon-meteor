package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ActivityHistogram;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogCodec;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogImporter;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerTracker;
import com.logvex.addon.chatlog.core.SessionSummary;
import com.logvex.addon.chatlog.core.StatsSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogToolsTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "server", "s1");
    }

    // ------------------------------------------------------------------ ActivityHistogram

    @Test
    void histogramBucketsAndReportsPeak() {
        ActivityHistogram histogram = new ActivityHistogram(1000L, 10);
        // Three messages in the bucket starting at 5000, one in the bucket at 7000.
        histogram.record(5100);
        histogram.record(5200);
        histogram.record(5300);
        histogram.record(7100);

        assertEquals(4, histogram.total(10));
        assertEquals(3, histogram.peak(10));
        List<ActivityHistogram.Bucket> buckets = histogram.recent(10);
        // Empty buckets between the two active ones are materialised so the gap is visible.
        assertTrue(buckets.size() >= 3);
        assertEquals(5000, buckets.get(0).start());
    }

    @Test
    void histogramDropsOldBucketsPastCapacity() {
        ActivityHistogram histogram = new ActivityHistogram(1000L, 3);
        for (int i = 0; i < 10; i++) histogram.record(i * 1000L + 10);
        List<ActivityHistogram.Bucket> buckets = histogram.recent(10);
        assertEquals(3, buckets.size(), "capacity must bound the number of buckets");
    }

    @Test
    void histogramClearResets() {
        ActivityHistogram histogram = new ActivityHistogram(1000L, 5);
        histogram.record(1000);
        assertFalse(histogram.isEmpty());
        histogram.clear();
        assertTrue(histogram.isEmpty());
        assertEquals(0, histogram.peak(5));
    }

    // ------------------------------------------------------------------ SessionSummary

    @Test
    void sessionSummaryDescribesAnEmptySession() {
        assertTrue(SessionSummary.isEmpty(StatsSnapshot.empty()));
        assertEquals("Session ended: no messages recorded.", SessionSummary.headline(StatsSnapshot.empty()));
        assertTrue(SessionSummary.lines(StatsSnapshot.empty()).isEmpty());
    }

    @Test
    void sessionSummaryReportsCounts() {
        StatsSnapshot snapshot = new StatsSnapshot(
            1000, 61000, 60000, 120, 120.0, 4,
            List.of("Notch (90)", "Alex (30)"),
            Map.of(MessageType.PUBLIC_CHAT, 118L, MessageType.JOIN, 2L),
            1, 1, 0, 0, Map.of());
        List<String> lines = SessionSummary.lines(snapshot);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("120 messages"));
        assertTrue(lines.get(0).contains("4 players"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Most active")));
    }

    // ------------------------------------------------------------------ PlayerTracker exclusions

    @Test
    void trackerIgnoresExcludedPlayers() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.setExcluded(List.of("SpamBot"));
        tracker.record(entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello"));
        tracker.record(entry(2000, MessageType.PUBLIC_CHAT, "spambot", "buy gold"));

        assertEquals(1, tracker.size());
        assertNotNull(tracker.get("Notch"));
        assertNull(tracker.get("spambot"));
        assertTrue(tracker.isExcluded("SpamBot"));
    }

    @Test
    void trackerForgetRemovesOnePlayer() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.record(entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello"));
        assertTrue(tracker.forget("Notch"));
        assertEquals(0, tracker.size());
        assertFalse(tracker.forget("Notch"));
    }

    @Test
    void trackerExclusionsAreCaseInsensitiveAndTrimmable() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.setExcluded(List.of("  AlEx  "));
        assertTrue(tracker.isExcluded("alex"));
        assertTrue(tracker.isExcluded("ALEX"));
    }

    @Test
    void trackerStopsCountingOnceExcluded() {
        PlayerTracker tracker = new PlayerTracker();
        tracker.record(entry(1000, MessageType.PUBLIC_CHAT, "Notch", "one"));
        tracker.setExcluded(List.of("Notch"));
        tracker.record(entry(2000, MessageType.PUBLIC_CHAT, "Notch", "two"));
        PlayerActivity activity = tracker.get("Notch");
        assertEquals(1, activity.messageCount(), "exclusion must apply to new entries only");
    }

    // ------------------------------------------------------------------ LogImporter round trips

    @Test
    void importsNativeCompressedSegment(@TempDir Path dir) throws IOException {
        List<ChatEntry> entries = List.of(
            entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello world"),
            entry(2000, MessageType.JOIN, "Alex", "Alex joined the game"));
        Path file = dir.resolve("segment.log.gz");
        Files.write(file, LogCodec.encode(entries, 6));

        LogImporter.Result result = LogImporter.read(file);
        assertEquals(2, result.entries().size());
        assertEquals("native log", result.format());
        assertEquals("hello world", result.entries().get(0).content());
    }

    @Test
    void importsJsonAndCsvAndText(@TempDir Path dir) throws IOException {
        ChatEntry one = entry(1000, MessageType.PUBLIC_CHAT, "Notch", "hello, world");

        Path json = dir.resolve("a.json");
        LogExporter jsonExporter = new LogExporter(LogExporter.Format.JSON);
        jsonExporter.open(json);
        jsonExporter.write(one);
        jsonExporter.close();
        LogImporter.Result jsonResult = LogImporter.read(json);
        assertEquals(1, jsonResult.entries().size());
        assertEquals("hello, world", jsonResult.entries().get(0).content());

        Path csv = dir.resolve("a.csv");
        LogExporter csvExporter = new LogExporter(LogExporter.Format.CSV);
        csvExporter.open(csv);
        csvExporter.write(one);
        csvExporter.close();
        LogImporter.Result csvResult = LogImporter.read(csv);
        assertEquals(1, csvResult.entries().size());
        assertEquals("hello, world", csvResult.entries().get(0).content());
        assertEquals(MessageType.PUBLIC_CHAT, csvResult.entries().get(0).type());

        Path txt = dir.resolve("a.txt");
        LogExporter txtExporter = new LogExporter(LogExporter.Format.TXT);
        txtExporter.open(txt);
        txtExporter.write(one);
        txtExporter.close();
        LogImporter.Result txtResult = LogImporter.read(txt);
        assertEquals(1, txtResult.entries().size());
        assertEquals("Notch", txtResult.entries().get(0).sender());
        assertEquals("hello, world", txtResult.entries().get(0).content());
    }

    @Test
    void importerReportsUnreadableLinesInsteadOfDroppingThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("messy.txt");
        Files.writeString(file, String.join("\n",
            "[2024-01-01 10:00:00] Notch: hello",
            "this line is not a log entry",
            "[2024-01-01 10:00:01] Alex: hi there"), StandardCharsets.UTF_8);

        LogImporter.Result result = LogImporter.read(file);
        assertEquals(2, result.entries().size());
        assertEquals(1, result.skipped().size());
        assertEquals(2, result.skipped().get(0).lineNumber());
        assertTrue(result.describe("messy.txt").contains("skipped line"));
    }

    @Test
    void importerHandlesCsvQuoting() {
        List<String> cells = LogImporter.splitCsv("1,\"a, b\",\"say \"\"hi\"\"\",x");
        assertEquals(4, cells.size());
        assertEquals("a, b", cells.get(1));
        assertEquals("say \"hi\"", cells.get(2));
    }

    @Test
    void importerRejectsAMissingFile(@TempDir Path dir) {
        try {
            LogImporter.read(dir.resolve("nope.txt"));
            org.junit.jupiter.api.Assertions.fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not exist"));
        }
    }
}
