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

        // With no requester named, the caller is the local client, so the wording is second person.
        assertEquals("Your ping is 42 ms (good).",
            StatsCommands.answer("ping", s, opts).orElseThrow());
        assertEquals("TPS 19.6 / 20.", StatsCommands.answer("tps", s, opts).orElseThrow());
        assertEquals("144 FPS.", StatsCommands.answer("fps", s, opts).orElseThrow());
        assertTrue(StatsCommands.answer("coords", s, opts).orElseThrow().startsWith("XYZ 120 64 -36"));
        assertEquals("Health 17.5/20, food 15.", StatsCommands.answer("health", s, opts).orElseThrow());
        assertEquals("mc.example.net, up 2h 5m.", StatsCommands.answer("server", s, opts).orElseThrow());
        assertEquals("World time 12:00 day.", StatsCommands.answer("time", s, opts).orElseThrow());
        assertEquals("12 players visible.",
            StatsCommands.answer("players", s, opts).orElseThrow());
        assertEquals("Session up 2h 5m.", StatsCommands.answer("uptime", s, opts).orElseThrow());
        assertEquals("World overworld (minecraft:overworld).",
            StatsCommands.answer("world", s, opts).orElseThrow());
        assertEquals("Speed 5.43 blocks/s.", StatsCommands.answer("speed", s, opts).orElseThrow());
    }

    @Test
    @DisplayName("the player count is described as visible, not as the server total")
    void playersIsHonestAboutItsSource() {
        // The client only knows the tab list, so the wording must not imply a server-wide figure.
        String answer = StatsCommands.answer("players", sample(),
            StatsCommands.Options.DEFAULTS).orElseThrow();

        assertTrue(answer.contains("visible"), answer);
        assertFalse(answer.contains("of 60"), "the tab list cannot know the cap: " + answer);
    }

    @Test
    @DisplayName("a figure the client cannot measure is declined, never invented")
    void unavailableFiguresAreNotInvented() {
        StatsCommands.Options opts = StatsCommands.Options.DEFAULTS;
        ServerStats notMoving = new ServerStats(
            true, "mc.example.net", 20f, 42, 0, 0, 0L,
            "", "", "", 0L, 20f, 20f, 20, 0, 64, 0, "", 0.0, 144, 0L, 0L);

        // No world name and no dimension: the command says it does not know.
        assertEquals("World ? (?).", StatsCommands.answer("world", notMoving, opts).orElseThrow());
        // No session clock yet.
        assertTrue(StatsCommands.answer("uptime", notMoving, opts).orElseThrow()
            .contains("not available"));
        // No players visible.
        assertTrue(StatsCommands.answer("players", notMoving, opts).orElseThrow()
            .contains("No players visible"));
        // Standing still.
        assertEquals("Not moving.", StatsCommands.answer("speed", notMoving, opts).orElseThrow());
    }

    @Test
    @DisplayName("ping reports the requester's latency, not this client's")
    void pingAnswersForTheRequester() {
        ServerStats s = sample();

        // The tab list says Steve is on 250 ms; this client's own figure is 42.
        assertEquals("Steve's ping is 250 ms (fair).", StatsCommands.answer("ping", s,
            StatsCommands.Options.DEFAULTS, 250, "Steve").orElseThrow());

        // A local player's own reply stays second person.
        assertEquals("Your ping is 42 ms (good).", StatsCommands.answer("ping", s,
            StatsCommands.Options.DEFAULTS, 42, "").orElseThrow());
    }

    @Test
    @DisplayName("an unknown requester ping is reported as unknown, never borrowed from this client")
    void unknownRequesterPingIsNotFabricated() {
        ServerStats s = sample();

        String answer = StatsCommands.answer("ping", s, StatsCommands.Options.DEFAULTS, -1,
            "Steve").orElseThrow();

        assertTrue(answer.contains("not available"), answer);
        assertFalse(answer.contains("42"), "this client's latency must not be attributed: " + answer);
    }

    @Test
    @DisplayName("ping declines when there is no world to measure against")
    void pingNeedsAWorld() {
        assertEquals("Not connected.", StatsCommands.answer("ping", ServerStats.OFFLINE,
            StatsCommands.Options.DEFAULTS, 30, "Steve").orElseThrow());
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
