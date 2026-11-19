package com.logvex.addon.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat command table: which verb answers, and how it words the answer. */
class StatsCommandsTest {

    private static ServerStats sample() {
        return new ServerStats(
            true, "mc.example.net", 19.6f, 42, 12, 60, 7_500_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L,
            17.5f, 20f, 15, 120.4, 64.0, -35.9, "north", 5.43, 144, 512L, 4096L);
    }

    @Test
    @DisplayName("each built-in verb answers with its own sentence")
    void verbsAnswer() {
        ServerStats s = sample();
        StatsCommands.Options opts = StatsCommands.Options.DEFAULTS;

        assertEquals("Ping 42 ms (good).", StatsCommands.answer("ping", s, opts).orElseThrow());
        assertEquals("TPS 19.6 / 20.", StatsCommands.answer("tps", s, opts).orElseThrow());
        assertEquals("144 FPS.", StatsCommands.answer("fps", s, opts).orElseThrow());
        assertTrue(StatsCommands.answer("coords", s, opts).orElseThrow().startsWith("XYZ 120 64 -36"));
        assertEquals("Health 17.5/20, food 15.", StatsCommands.answer("health", s, opts).orElseThrow());
        assertEquals("mc.example.net, up 2h 5m.", StatsCommands.answer("server", s, opts).orElseThrow());
        assertEquals("World time 12:00 day.", StatsCommands.answer("time", s, opts).orElseThrow());
    }

    @Test
    @DisplayName("the stats verb joins the brief or full figures with the configured separator")
    void statsVerbHonoursDetail() {
        ServerStats s = sample();
        String brief = StatsCommands.answer("stats", s,
            new StatsCommands.Options(true, "ping", "tps", "stats", "coords", "health", "fps",
                "server", "time", ServerStats.Detail.BRIEF, " / ")).orElseThrow();
        String full = StatsCommands.answer("stats", s,
            new StatsCommands.Options(true, "ping", "tps", "stats", "coords", "health", "fps",
                "server", "time", ServerStats.Detail.FULL, " / ")).orElseThrow();

        assertTrue(brief.contains(" / "));
        assertFalse(brief.contains("Biome"));
        assertTrue(full.contains("Biome"));
        assertTrue(full.length() > brief.length());
    }

    @Test
    @DisplayName("a verb only answers when it is the first word, and case does not matter")
    void verbMatching() {
        StatsCommands.Options opts = StatsCommands.Options.DEFAULTS;
        assertTrue(StatsCommands.answer("PING", sample(), opts).isPresent());
        assertTrue(StatsCommands.answer("  ping  ", sample(), opts).isPresent());
        assertFalse(StatsCommands.answer("pingpong", sample(), opts).isPresent());
        assertFalse(StatsCommands.answer("what is the ping", sample(), opts).isPresent());
    }

    @Test
    @DisplayName("an unknown verb is declined so the assistant can try its other sources")
    void unknownVerbDeclined() {
        assertTrue(StatsCommands.answer("dance", sample(), StatsCommands.Options.DEFAULTS).isEmpty());
        assertTrue(StatsCommands.answer("", sample(), StatsCommands.Options.DEFAULTS).isEmpty());
        assertTrue(StatsCommands.answer(null, sample(), StatsCommands.Options.DEFAULTS).isEmpty());
    }

    @Test
    @DisplayName("disabling the feature or an individual verb declines it")
    void switchesAreHonoured() {
        StatsCommands.Options allOff = new StatsCommands.Options(false, "ping", "tps", "stats",
            "coords", "health", "fps", "server", "time", ServerStats.Detail.BRIEF, " | ");
        assertTrue(StatsCommands.answer("ping", sample(), allOff).isEmpty());

        StatsCommands.Options noPing = new StatsCommands.Options(true, "notping", "tps", "stats",
            "coords", "health", "fps", "server", "time", ServerStats.Detail.BRIEF, " | ");
        assertTrue(StatsCommands.answer("ping", sample(), noPing).isEmpty());
        assertTrue(StatsCommands.answer("notping", sample(), noPing).isPresent());
    }

    @Test
    @DisplayName("every verb reports a clean sentence when there is no world")
    void offlineAnswers() {
        StatsCommands.Options opts = StatsCommands.Options.DEFAULTS;
        for (String verb : new String[]{"ping", "tps", "stats", "coords", "health", "fps", "server", "time"}) {
            Optional<String> answer = StatsCommands.answer(verb, ServerStats.OFFLINE, opts);
            assertTrue(answer.isPresent(), verb);
            assertEquals("Not connected.", answer.orElseThrow(), verb);
        }
    }

    @Test
    @DisplayName("a lagging server is called out in the tps answer")
    void laggingIsCalledOut() {
        ServerStats lagging = new ServerStats(true, "s", 11.2f, 90, 3, 0, 1000L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L);
        String answer = StatsCommands.answer("tps", lagging, StatsCommands.Options.DEFAULTS).orElseThrow();
        assertTrue(answer.contains("lagging"));
    }

    @Test
    @DisplayName("the usage line lists every configured verb for the help text")
    void usageListsVerbs() {
        String usage = StatsCommands.usage(StatsCommands.Options.DEFAULTS);
        for (String verb : new String[]{"ping", "tps", "stats", "coords", "health", "fps", "server", "time"}) {
            assertTrue(usage.contains(verb), verb);
        }
    }

    @Test
    @DisplayName("blank custom verb names fall back to the defaults rather than becoming unmatchable")
    void blankNamesFallBack() {
        StatsCommands.Options opts = new StatsCommands.Options(true, "", "  ", null, null, null,
            null, null, null, null, null);
        assertEquals("ping", opts.ping());
        assertEquals("tps", opts.tps());
        assertEquals("stats", opts.stats());
        assertEquals(" | ", opts.separator());
        assertEquals(ServerStats.Detail.BRIEF, opts.detail());
    }
}
