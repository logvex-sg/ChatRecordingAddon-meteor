package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.ChatActivity;
import com.logvex.addon.chatlog.core.ReportLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report log keeps what was filed, and {@code !reports} reads it back. */
class ReportLogTest {

    private static ReportLog.Record record(long at, String reporter, String reason, boolean ok) {
        return new ReportLog.Record(at, reporter, reason, "report", ok, ok ? "queued" : "failed");
    }

    /** Runs {@code line} as the local player, which is who may read the report log. */
    private static AssistantBrain.Decision localCommand(AssistantBrain brain, String line) {
        return brain.command(com.logvex.addon.assistant.core.SenderIdentity.local("Alex", null),
            line, AssistantConfig.DEFAULTS, System.currentTimeMillis(),
            com.logvex.addon.stats.ServerStats.OFFLINE, null, null, new ChatActivity());
    }

    @Test
    @DisplayName("the newest record is first")
    void newestFirst() {
        ReportLog log = new ReportLog();
        log.add(record(1, "Alex", "first", true));
        log.add(record(2, "Alex", "second", true));
        assertEquals("second", log.recent(1).get(0).reason());
    }

    @Test
    @DisplayName("the cap drops the oldest record")
    void capacityDropsOldest() {
        ReportLog log = new ReportLog(2);
        log.add(record(1, "Alex", "a", true));
        log.add(record(2, "Alex", "b", true));
        log.add(record(3, "Alex", "c", true));
        assertEquals(2, log.size());
        // "a" would also match the reporter "Alex", so assert on the dropped reason directly.
        assertTrue(log.all().stream().noneMatch(r -> r.reason().equals("a")),
            "the oldest record must be dropped");
    }

    @Test
    @DisplayName("a failed report is kept, and counted")
    void failuresAreKept() {
        ReportLog log = new ReportLog();
        log.add(record(1, "Alex", "went nowhere", false));
        assertEquals(1, log.failures());
        assertFalse(log.all().get(0).delivered());
    }

    @Test
    @DisplayName("search matches the reporter and the reason")
    void searchMatchesBoth() {
        ReportLog log = new ReportLog();
        log.add(record(1, "Alex", "griefer at spawn", true));
        log.add(record(2, "Steve", "asked for help", true));
        assertEquals(1, log.search("alex").size());
        assertEquals(1, log.search("griefer").size());
        assertEquals(2, log.search("").size());
    }

    @Test
    @DisplayName("a log survives a round trip through the settings file")
    void roundTrip() {
        ReportLog log = new ReportLog();
        log.add(record(1_700_000_000_000L, "Alex", "griefer at spawn", true));
        log.add(record(1_700_000_001_000L, "Steve", "back\\slash and | pipe", false));

        ReportLog reloaded = new ReportLog();
        reloaded.loadAll(log.encodeAll());

        assertEquals(2, reloaded.size());
        List<ReportLog.Record> all = reloaded.all();
        assertEquals("Steve", all.get(0).reporter());
        assertEquals("back\\slash and | pipe", all.get(0).reason());
        assertEquals("griefer at spawn", all.get(1).reason());
        assertEquals(1_700_000_000_000L, all.get(1).atMs());
        assertFalse(all.get(0).delivered());
    }

    @Test
    @DisplayName("a malformed line is skipped, not thrown")
    void malformedLineSkipped() {
        ReportLog log = new ReportLog();
        log.loadAll(List.of("not a record", "", "1\u0001report\u0001true"));
        assertEquals(0, log.size());
    }

    @Test
    @DisplayName("!reports lists what was filed")
    void reportsCommandListsFiled() {
        ReportLog log = new ReportLog();
        log.add(record(1_700_000_000_000L, "Alex", "griefer at spawn", true));

        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        brain.setReportLogSource(() -> log);
        AssistantBrain.Decision decision = localCommand(brain, "!reports");
        assertTrue(decision.hasEmbed(), "the list is a block, not a paragraph");
        assertTrue(decision.text().contains("griefer at spawn")
            || decision.lines(48, com.logvex.addon.assistant.core.ReplyFormat.Style.BOX)
                .toString().contains("griefer"));
    }

    @Test
    @DisplayName("!reports says so when the log is not available")
    void reportsCommandWithoutLog() {
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        brain.setReportLogSource(() -> null);
        AssistantBrain.Decision decision = localCommand(brain, "!reports");
        assertFalse(decision.hasEmbed());
        assertTrue(decision.text().contains("unavailable"));
    }

    @Test
    @DisplayName("a queued report is settled with the real delivery outcome")
    void settleUpdatesOutcome() {
        ReportLog log = new ReportLog();
        ReportLog.Record queued = record(1_700_000_000_000L, "Alex", "griefer", false);
        log.add(queued);

        assertTrue(log.settle(queued, true, "delivered"));
        assertTrue(log.all().get(0).delivered(), "a delivered report must not stay marked failed");
        assertEquals("delivered", log.all().get(0).note());
        assertEquals(0, log.failures());
    }

    @Test
    @DisplayName("settling one record leaves a same-millisecond sibling alone")
    void settleIsIdentityBased() {
        ReportLog log = new ReportLog();
        ReportLog.Record first = record(500L, "Alex", "one", false);
        ReportLog.Record second = record(500L, "Alex", "two", false);
        log.add(first);
        log.add(second);

        log.settle(first, true, "delivered");

        // Only "one" settles; "two" is still failed despite sharing the timestamp.
        assertEquals(1, log.failures());
        ReportLog.Record two = log.all().stream()
            .filter(r -> r.reason().equals("two")).findFirst().orElseThrow();
        assertFalse(two.delivered());
    }

    @Test
    @DisplayName("settling an unknown record is a no-op, not a throw")
    void settleUnknownIsNoop() {
        ReportLog log = new ReportLog();
        assertFalse(log.settle(record(1, "Alex", "never added", false), true, "x"));
        assertEquals(0, log.size());
    }

    @Test
    @DisplayName("a stranger cannot read the operator's report history")
    void reportsIsLocalOnly() {
        ReportLog log = new ReportLog();
        log.add(record(1, "Owner", "private reason", true));
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        brain.setReportLogSource(() -> log);

        AssistantBrain.Decision decision = brain.command(
            com.logvex.addon.assistant.core.SenderIdentity.unverified("Stranger"),
            "!reports", AssistantConfig.DEFAULTS, System.currentTimeMillis(),
            com.logvex.addon.stats.ServerStats.OFFLINE, null, null, new ChatActivity());

        assertFalse(decision.hasEmbed(), "no card may be built for a non-local speaker");
        assertFalse(decision.text().contains("private reason"));
        assertTrue(decision.text().contains("owner"));
    }

    @Test
    @DisplayName("!reports on an empty log says there are none, not that it is broken")
    void reportsCommandEmpty() {
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        brain.setReportLogSource(ReportLog::new);
        AssistantBrain.Decision decision = localCommand(brain, "!reports");
        assertTrue(decision.text().contains("No reports"), decision.text());
    }
}
