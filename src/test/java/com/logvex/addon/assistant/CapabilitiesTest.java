package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.Capabilities;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of {@link Capabilities} is honesty: a disconnected client must say it cannot see server
 * figures rather than inventing them. These tests pin that, and pin that the answer is derived from
 * state rather than hardcoded.
 */
class CapabilitiesTest {

    private static Capabilities offline() {
        return Capabilities.of(ServerStats.OFFLINE, AssistantConfig.DEFAULTS, false, false, 0, 0);
    }

    @Test
    @DisplayName("a disconnected client cannot see server figures")
    void offlineCannotSeeServerFigures() {
        Capabilities caps = offline();
        assertFalse(caps.connected());
        assertFalse(caps.available().stream().anyMatch(a -> a.name().equals("Server figures")));
        assertTrue(caps.unavailable().stream().anyMatch(a -> a.name().equals("Server figures")));
    }

    @Test
    @DisplayName("the reason for an unavailable ability is stated, not left blank")
    void unavailableAbilitiesExplainWhy() {
        for (Capabilities.Ability ability : offline().unavailable()) {
            assertFalse(ability.detail().isBlank(),
                "an unavailable ability must say why: " + ability.name());
        }
    }

    @Test
    @DisplayName("an in-world client can see server figures and the player list")
    void inWorldSeesServerFigures() {
        ServerStats inWorld = new ServerStats(true, "example.net", "example.net", false, 20f, 42,
            5, 20, 60_000L, "overworld", "world", "plains", 1_000L, 1L, "clear", 60, 512L, 2048L,
            java.util.List.of("Alex"), com.logvex.addon.stats.PlayerView.EMPTY, 20f, 20f, 20f, 100L, "1.21.1",
            "vanilla", 60_000L, 0L, "normal", "1.21.1", "0.16.0", 12);
        Capabilities caps = Capabilities.of(inWorld, AssistantConfig.DEFAULTS, true, true, 27, 4);
        assertTrue(caps.connected());
        assertTrue(caps.available().stream().anyMatch(a -> a.name().equals("Server figures")));
        assertTrue(caps.available().stream().anyMatch(a -> a.name().equals("Player list")));
        assertTrue(caps.available().stream().anyMatch(a -> a.name().equals("Reports")));
    }

    @Test
    @DisplayName("reports are only available when a channel is configured")
    void reportsFollowConfiguration() {
        Capabilities withReports =
            Capabilities.of(ServerStats.OFFLINE, AssistantConfig.DEFAULTS, true, false, 0, 0);
        Capabilities withoutReports =
            Capabilities.of(ServerStats.OFFLINE, AssistantConfig.DEFAULTS, false, false, 0, 0);
        assertTrue(withReports.reportsEnabled());
        assertFalse(withoutReports.reportsEnabled());
        assertTrue(withoutReports.unavailable().stream()
            .anyMatch(a -> a.name().equals("Reports") && a.detail().contains("configured")));
    }

    @Test
    @DisplayName("triggers are unavailable when the table is empty")
    void emptyTriggerTableIsUnavailable() {
        Capabilities none = Capabilities.of(ServerStats.OFFLINE, AssistantConfig.DEFAULTS,
            false, false, 0, 0);
        assertTrue(none.unavailable().stream()
            .anyMatch(a -> a.name().equals("Triggers") && a.detail().contains("no rules")));
        Capabilities armed = Capabilities.of(ServerStats.OFFLINE, AssistantConfig.DEFAULTS,
            false, false, 27, 0);
        assertTrue(armed.available().stream().anyMatch(a -> a.name().equals("Triggers")));
    }

    @Test
    @DisplayName("the summary names both what works and what does not")
    void summaryNamesBothSides() {
        String summary = offline().summary();
        assertTrue(summary.contains("Not available"), summary);
        assertTrue(summary.contains("Server figures"), summary);
    }

    @Test
    @DisplayName("nothing available is reported as such rather than an empty list")
    void nothingAvailable() {
        Capabilities caps = new Capabilities(false, false, false, false, false, false, 0, 0);
        assertTrue(caps.nothing());
        assertEquals("Nothing available right now.", caps.summary());
    }
}
