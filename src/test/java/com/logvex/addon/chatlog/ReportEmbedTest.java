package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.Json;
import com.logvex.addon.chatlog.core.ReportEmbed;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report card posted to Discord, and the role ping that goes with it.
 *
 * <p>The ping is the part worth testing. A report that silently fails to mention anyone is worse
 * than no feature at all, because the reporter believes they have been heard. The other risk is the
 * opposite: a mention list that is too permissive and pings the whole server.
 */
class ReportEmbedTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return (Map<String, Object>) Json.toMap(json);
    }

    // ------------------------------------------------------------------ the mention

    @Test
    @DisplayName("a bare role id becomes a real Discord role mention")
    void bareIdBecomesMention() {
        assertEquals("<@&123456789>", ReportEmbed.mention("123456789"));
    }

    @Test
    @DisplayName("an already-wrapped role mention is passed through once, not double-wrapped")
    void wrappedMentionUnchanged() {
        assertEquals("<@&123456789>", ReportEmbed.mention("<@&123456789>"));
    }

    @Test
    @DisplayName("no role configured means no mention, not a dead @here")
    void noRoleNoMention() {
        // @here would render as plain text because only roles are allowed to parse; showing it would
        // look like a ping that failed.
        assertEquals("", ReportEmbed.mention(null));
        assertEquals("", ReportEmbed.mention(""));
        assertEquals("", ReportEmbed.mention("   "));
    }

    @Test
    @DisplayName("a role name that is not an id is shown as text, not sent as a broken mention")
    void nonIdPassedAsText() {
        assertEquals("Moderators", ReportEmbed.mention("Moderators"));
    }

    // ------------------------------------------------------------------ the card

    @Test
    @DisplayName("the card names the reporter and carries the reason")
    void cardCarriesReporterAndReason() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Steve",
            "griefing spawn", "mc.example.net", "report");
        Map<String, Object> map = card.toMap();
        assertEquals("New report", map.get("title"));
        assertEquals("griefing spawn", map.get("description"));
        String json = Json.toJson(map);
        assertTrue(json.contains("Steve"), json);
        assertTrue(json.contains("mc.example.net"), json);
    }

    @Test
    @DisplayName("a mod call is titled differently from a report")
    void modCardTitle() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "need help",
            "", "mod");
        assertEquals("Moderator requested", card.toMap().get("title"));
    }

    @Test
    @DisplayName("an empty reason is replaced rather than leaving a blank card")
    void emptyReason() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "   ", "", "report");
        assertEquals("(no reason given)", card.toMap().get("description"));
    }

    @Test
    @DisplayName("a blank reporter is replaced rather than naming nobody")
    void blankReporter() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "", "reason", "", "report");
        assertTrue(Json.toJson(card.toMap()).contains("someone"));
    }

    @Test
    @DisplayName("a very long reason is clipped, so the webhook does not reject the card")
    void longReasonClipped() {
        String reason = "x".repeat(ReportEmbed.REASON_LIMIT + 500);
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", reason, "", "report");
        String description = String.valueOf(card.toMap().get("description"));
        assertEquals(ReportEmbed.REASON_LIMIT, description.length());
        assertTrue(description.endsWith("\u2026"));
    }

    @Test
    @DisplayName("the report card uses the report colour")
    void reportColour() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "reason", "", "report");
        // Discord carries the colour as a packed integer, so that is what must be sent.
        assertEquals(ReportEmbed.REPORT_COLOR, ((Number) card.toMap().get("color")).intValue());
    }

    // ------------------------------------------------------------------ the payload

    @Test
    @DisplayName("the payload pings only roles, never everyone")
    void allowedMentionsRolesOnly() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "r", "", "report");
        Map<String, Object> root = parse(ReportEmbed.payload("Log Client", "123456789", card));
        @SuppressWarnings("unchecked")
        Map<String, Object> allowed = (Map<String, Object>) root.get("allowed_mentions");
        assertEquals(List.of("roles"), allowed.get("parse"));
        assertFalse(Json.toJson(allowed).contains("everyone"));
    }

    @Test
    @DisplayName("the payload carries the mention above the card")
    void payloadContentIsMention() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "r", "", "report");
        Map<String, Object> root = parse(ReportEmbed.payload("Log Client", "123456789", card));
        assertEquals("<@&123456789>", root.get("content"));
    }

    @Test
    @DisplayName("with no role the payload omits content entirely instead of sending an empty line")
    void payloadOmitsContentWithoutRole() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "r", "", "report");
        Map<String, Object> root = parse(ReportEmbed.payload("Log Client", "", card));
        assertFalse(root.containsKey("content"), "an empty content would post a stray blank line");
        @SuppressWarnings("unchecked")
        List<Object> embeds = (List<Object>) root.get("embeds");
        assertEquals(1, embeds.size());
    }

    @Test
    @DisplayName("the payload names the webhook user, falling back when blank")
    void payloadUsername() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "r", "", "report");
        assertEquals("Log Client", parse(ReportEmbed.payload("", "", card)).get("username"));
        assertEquals("Watch", parse(ReportEmbed.payload("Watch", "", card)).get("username"));
    }

    @Test
    @DisplayName("the payload is valid JSON that survives a round trip")
    void payloadIsJson() {
        DiscordEmbed card = ReportEmbed.card(WebhookEmbeds.Style.defaults(), "Alex", "a \"quoted\" reason",
            "server", "report");
        String json = ReportEmbed.payload("Log Client", "123456789", card);
        Map<String, Object> root = parse(json);
        assertTrue(root.containsKey("embeds"));
        // A quote inside the reason must not break the document.
        assertTrue(Json.toJson(root.get("embeds")).contains("quoted"));
    }
}
