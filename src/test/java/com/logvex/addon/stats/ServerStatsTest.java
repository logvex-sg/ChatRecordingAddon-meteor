package com.logvex.addon.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stats value type: formatting, verdicts and the brief/full views. */
class ServerStatsTest {

    private static ServerStats sample() {
        return new ServerStats(
            true, "mc.example.net", 19.6f, 42, 12, 60, 7_500_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L,
            17.5f, 20f, 15, 120.4, 64.0, -35.9, "north", 5.43, 144, 512L, 4096L);
    }

    @Test
    @DisplayName("the tick rate is clamped to the 0..20 range and shown to one decimal")
    void tpsFormatting() {
        assertEquals("20.0", ServerStats.formatTps(25f));
        assertEquals("19.6", ServerStats.formatTps(19.6f));
        assertEquals("0.0", ServerStats.formatTps(-1f));
    }

    @Test
    @DisplayName("mspt is the reciprocal of the tick rate and absent when the tick rate is unknown")
    void mspt() {
        assertEquals(1000f / 19.6f, sample().mspt(), 0.001f);
        assertEquals(-1f, ServerStats.OFFLINE.mspt(), 0.0001f);
    }

    @Test
    @DisplayName("a low tick rate is reported as lagging, an offline snapshot never is")
    void lagging() {
        assertFalse(sample().lagging());
        ServerStats lagging = new ServerStats(true, "s", 12.5f, 30, 1, 0, 1000L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L);
        assertTrue(lagging.lagging());
        assertFalse(ServerStats.OFFLINE.lagging());
    }

    @Test
    @DisplayName("the verdict separates offline, lagging, poor, fair and good")
    void verdicts() {
        assertEquals("offline", ServerStats.OFFLINE.verdict());
        assertEquals("good", sample().verdict());
        assertEquals("lagging", new ServerStats(true, "s", 10f, 20, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L).verdict());
        assertEquals("poor", new ServerStats(true, "s", 20f, 500, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L).verdict());
        assertEquals("fair", new ServerStats(true, "s", 20f, 250, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L).verdict());
    }

    @Test
    @DisplayName("latency maps to the five-bar scale and is absent when unknown")
    void latencyBars() {
        assertEquals(5, sample().latencyBars());
        assertEquals(-1, ServerStats.OFFLINE.latencyBars());
        assertEquals(1, new ServerStats(true, "s", 20f, 900, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L).latencyBars());
    }

    @Test
    @DisplayName("uptime reads as hours and minutes, then minutes and seconds, then seconds")
    void uptimeFormatting() {
        assertEquals("2h 5m", ServerStats.formatUptime(7_500_000L));
        assertEquals("3m 20s", ServerStats.formatUptime(200_000L));
        assertEquals("47s", ServerStats.formatUptime(47_000L));
        assertEquals("0s", ServerStats.formatUptime(-5L));
    }

    @Test
    @DisplayName("the world clock wraps every 24000 ticks and knows day from night")
    void worldTimeFormatting() {
        assertEquals("06:00 day", ServerStats.formatWorldTime(0L));
        assertEquals("12:00 day", ServerStats.formatWorldTime(6_000L));
        assertEquals("18:00 day", ServerStats.formatWorldTime(12_000L));
        assertEquals("00:00 night", ServerStats.formatWorldTime(18_000L));
        // A full day later reads the same as the first day.
        assertEquals(ServerStats.formatWorldTime(6_000L), ServerStats.formatWorldTime(30_000L));
    }

    @Test
    @DisplayName("yaw maps to the four compass directions, wrapping through zero")
    void facingNames() {
        assertEquals("south", ServerStats.facingName(0f));
        assertEquals("west", ServerStats.facingName(90f));
        assertEquals("north", ServerStats.facingName(180f));
        assertEquals("east", ServerStats.facingName(-90f));
        assertEquals("south", ServerStats.facingName(360f));
        assertEquals("south", ServerStats.facingName(350f));
    }

    @Test
    @DisplayName("the brief view keeps to the connection figures, the full view adds the rest")
    void detailLevels() {
        List<ServerStats.Line> brief = sample().lines(ServerStats.Detail.BRIEF);
        List<ServerStats.Line> full = sample().lines(ServerStats.Detail.FULL);

        assertTrue(full.size() > brief.size());
        assertTrue(labels(brief).contains("TPS"));
        assertTrue(labels(brief).contains("Ping"));
        assertFalse(labels(brief).contains("Biome"));
        assertTrue(labels(full).contains("Biome"));
        assertTrue(labels(full).contains("Memory"));
    }

    @Test
    @DisplayName("an offline snapshot reports only that it is not connected")
    void offlineLines() {
        List<ServerStats.Line> lines = ServerStats.OFFLINE.lines(ServerStats.Detail.FULL);
        assertEquals(1, lines.size());
        assertEquals("Status", lines.get(0).label());
        assertEquals("not connected", lines.get(0).value());
    }

    @Test
    @DisplayName("join renders the lines as one separated line")
    void join() {
        assertEquals("Status not connected", ServerStats.OFFLINE.join(ServerStats.Detail.BRIEF, " | "));
        assertTrue(sample().join(ServerStats.Detail.BRIEF, " | ").startsWith("Server mc.example.net | TPS 19.6"));
    }

    @Test
    @DisplayName("null text fields and out-of-range numbers are normalised, not stored as-is")
    void normalisation() {
        ServerStats messy = new ServerStats(true, null, 99f, 10, -4, -1, -9L, null, null, null, 0L,
            -1f, -1f, -5, 0, 0, 0, null, -2.0, -60, -1L, -1L);
        assertEquals("", messy.server());
        assertEquals("", messy.biome());
        assertEquals("", messy.facing());
        assertEquals(ServerStats.TARGET_TPS, messy.tps());
        assertEquals(0, messy.players());
        assertEquals(0, messy.food());
        assertEquals(0.0, messy.speed());
        assertEquals(0, messy.fps());
        assertEquals(0L, messy.uptimeMs());
    }

    private static List<String> labels(List<ServerStats.Line> lines) {
        return lines.stream().map(ServerStats.Line::label).toList();
    }
}
