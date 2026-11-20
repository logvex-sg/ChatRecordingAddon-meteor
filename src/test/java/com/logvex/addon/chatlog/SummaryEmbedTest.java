package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat-summary report card: the embed an operator sends on demand.
 *
 * <p>The card is the deliverable, so the tests pin what a reader sees and what Discord will accept.
 * Discord silently rejects an embed that breaks its limits, which would look like the send button
 * doing nothing, so the limits are asserted rather than assumed.
 */
class SummaryEmbedTest {

    private static ChatDigest.Summary summary(int players, int highlights, long messages) {
        List<ChatDigest.PlayerLine> top = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            top.add(new ChatDigest.PlayerLine("Player" + i, 100 - i, 0, 0, 0));
        }
        List<String> words = new ArrayList<>();
        for (int i = 0; i < highlights; i++) words.add("word" + i);
        return new ChatDigest.Summary(0L, 3_600_000L, messages, players,
            Map.of(MessageType.PUBLIC_CHAT, messages), List.copyOf(top), List.copyOf(words), 2, 1, 0);
    }

    private static DiscordEmbed card(ChatDigest.Summary summary) {
        return WebhookEmbeds.summaryReport(WebhookEmbeds.Style.defaults(), summary,
            "Steve", "weekly roundup", "mc.example.net");
    }

    @Test
    @DisplayName("the card carries the figures a reader wants")
    void cardCarriesTheFigures() {
        DiscordEmbed embed = card(summary(3, 2, 120));
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (DiscordEmbed.Field field : embed.fields()) fields.put(field.name(), field.value());

        assertEquals("Steve", fields.get("Requested by"));
        assertEquals("weekly roundup", fields.get("Note"));
        assertEquals("120", fields.get("Messages"));
        assertEquals("3", fields.get("Players"));
        assertEquals("2", fields.get("Joins"));
        assertEquals("1", fields.get("Leaves"));
        assertEquals("0", fields.get("Deaths"));
        assertTrue(fields.containsKey("Window"), "the span the summary covers must be shown");
    }

    @Test
    @DisplayName("the most active players are listed with their message counts")
    void mostActiveIsListed() {
        DiscordEmbed embed = card(summary(3, 0, 50));
        String value = embed.fields().stream()
            .filter(f -> f.name().equals("Most active")).findFirst().orElseThrow().value();
        assertTrue(value.contains("**Player0**"), value);
        assertTrue(value.contains("100 msg"), value);
        assertTrue(value.contains("\n"), "each player belongs on its own line");
    }

    @Test
    @DisplayName("keyword highlights are shown as inline code")
    void highlightsAreCodeSpans() {
        DiscordEmbed embed = card(summary(1, 2, 10));
        String value = embed.fields().stream()
            .filter(f -> f.name().equals("Highlights")).findFirst().orElseThrow().value();
        assertEquals("`word0`, `word1`", value);
    }

    @Test
    @DisplayName("a card with no top players or highlights omits those fields")
    void emptySectionsAreOmitted() {
        DiscordEmbed embed = card(summary(0, 0, 0));
        assertFalse(embed.fields().stream().anyMatch(f -> f.name().equals("Most active")));
        assertFalse(embed.fields().stream().anyMatch(f -> f.name().equals("Highlights")));
    }

    @Test
    @DisplayName("an unnamed requester and an empty note add no fields")
    void blankAttributionIsOmitted() {
        DiscordEmbed embed = WebhookEmbeds.summaryReport(WebhookEmbeds.Style.defaults(),
            summary(1, 0, 5), "", "", "srv");
        assertFalse(embed.fields().stream().anyMatch(f -> f.name().equals("Requested by")));
        assertFalse(embed.fields().stream().anyMatch(f -> f.name().equals("Note")));
    }

    @Test
    @DisplayName("a long player list still fits Discord's field limit")
    void longListFitsTheFieldLimit() {
        // Discord rejects the whole embed when one field exceeds 1024 characters, which would look
        // like the send silently failing. Twenty players is the digest's default cap.
        DiscordEmbed embed = card(summary(20, 40, 5000));
        for (DiscordEmbed.Field field : embed.fields()) {
            assertTrue(field.name().length() <= DiscordEmbed.FIELD_NAME_LIMIT,
                "field name too long: " + field.name());
            assertTrue(field.value().length() <= DiscordEmbed.FIELD_VALUE_LIMIT,
                "field value too long for " + field.name() + ": " + field.value().length());
        }
        assertTrue(embed.fields().size() <= DiscordEmbed.MAX_FIELDS,
            "too many fields: " + embed.fields().size());
    }

    @Test
    @DisplayName("a note cannot smuggle a mention into the card")
    void noteCannotSmuggleAMention() {
        // The note is free text from chat. Discord renders "@everyone" in a field as plain text, but
        // the payload must not carry it into a content field where it would ping.
        DiscordEmbed embed = WebhookEmbeds.summaryReport(WebhookEmbeds.Style.defaults(),
            summary(1, 0, 5), "Steve", "@everyone look here", "srv");
        String note = embed.fields().stream()
            .filter(f -> f.name().equals("Note")).findFirst().orElseThrow().value();
        assertFalse(note.contains("@everyone"),
            "an at-sign run must be broken up so Discord cannot treat it as a ping: " + note);
    }

    @Test
    @DisplayName("the title identifies the card as a summary report")
    void titleIdentifiesTheCard() {
        assertTrue(String.valueOf(card(summary(1, 0, 1)).toMap().get("title")).contains("summary"));
    }
}
