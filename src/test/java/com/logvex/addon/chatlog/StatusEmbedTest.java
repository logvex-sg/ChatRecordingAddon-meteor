package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.EmbedColor;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-status card: the same figures as the HUD, rendered as one clean embed. */
class StatusEmbedTest {

    private static ServerStats sample() {
        return new ServerStats(
            true, "mc.example.net", 19.6f, 42, 12, 60, 7_500_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L,
            17.5f, 20f, 15, 120.4, 64.0, -35.9, "north", 5.43, 144, 512L, 4096L);
    }

    private static DiscordEmbed card(ServerStats stats) {
        return WebhookEmbeds.status(WebhookEmbeds.Style.defaults(), stats);
    }

    @Test
    @DisplayName("a healthy server gets a green card listing every figure")
    void healthyCard() {
        DiscordEmbed embed = card(sample());
        Map<String, Object> map = embed.toMap();

        assertEquals(EmbedColor.GREEN, map.get("color"));
        assertEquals("\uD83D\uDCE1 Server status", map.get("title"));
        assertEquals("**GOOD**", map.get("description"));

        String json = map.toString();
        assertTrue(json.contains("mc.example.net"));
        assertTrue(json.contains("19.6 / 20.0"));
        assertTrue(json.contains("42 ms"));
        assertTrue(json.contains("12/60"));
        assertTrue(json.contains("plains"));
    }

    @Test
    @DisplayName("the accent warns before the card is read: orange for lag, yellow for poor ping")
    void accentReflectsHealth() {
        ServerStats lagging = new ServerStats(true, "s", 11.0f, 40, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L);
        ServerStats poorPing = new ServerStats(true, "s", 20f, 500, 1, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L);

        assertEquals(EmbedColor.ORANGE, card(lagging).toMap().get("color"));
        assertEquals(EmbedColor.YELLOW, card(poorPing).toMap().get("color"));
        assertEquals(EmbedColor.RED, card(ServerStats.OFFLINE).toMap().get("color"));
    }

    @Test
    @DisplayName("an offline card says so plainly instead of showing zeroes")
    void offlineCard() {
        Map<String, Object> map = card(ServerStats.OFFLINE).toMap();
        String json = map.toString();

        assertEquals("**OFFLINE**", map.get("description"));
        assertTrue(json.contains("not connected"));
        assertFalse(json.contains("TPS"));
    }

    @Test
    @DisplayName("a null snapshot is treated as offline rather than throwing")
    void nullIsOffline() {
        assertEquals("**OFFLINE**", card(null).toMap().get("description"));
    }

    @Test
    @DisplayName("the card never pings anyone and carries no webhook URL")
    void mentionSafe() {
        String json = com.logvex.addon.chatlog.core.WebhookPayload.status(
            "Log Client", sample(), WebhookEmbeds.Style.defaults());

        assertTrue(json.contains("\"allowed_mentions\""));
        assertTrue(json.contains("\"parse\":[]") || json.contains("parse"));
        assertFalse(json.contains("@everyone"));
    }

    @Test
    @DisplayName("hidden players and an unknown maximum degrade to a plain count")
    void partialPlayerList() {
        ServerStats hidden = new ServerStats(true, "s", 20f, 40, 7, 0, 0L, "", "", "", 0L,
            20f, 20f, 20, 0, 0, 0, "", 0, 60, 0L, 0L);
        Map<String, Object> map = card(hidden).toMap();

        String fields = map.get("fields").toString();
        assertTrue(fields.contains("7"));
        assertFalse(fields.contains("7/0"));
    }

    @Test
    @DisplayName("every field is non-empty, so Discord does not reject the card")
    void fieldsAreValid() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields =
            (List<Map<String, Object>>) card(sample()).toMap().get("fields");
        for (Map<String, Object> field : fields) {
            assertFalse(String.valueOf(field.get("name")).isBlank());
            assertFalse(String.valueOf(field.get("value")).isBlank());
        }
    }
}
