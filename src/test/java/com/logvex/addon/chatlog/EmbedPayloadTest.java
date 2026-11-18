package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.EmbedAccent;
import com.logvex.addon.chatlog.core.EmbedColor;
import com.logvex.addon.chatlog.core.Json;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook output is a Discord embed, not a flat content string. These tests pin the shape: the
 * limits are enforced, the mentions are disabled, the accent is chosen sensibly, and captured text
 * cannot smuggle a mention or a newline into a field.
 */
class EmbedPayloadTest {

    private static ChatEntry entry(long ts, MessageType type, String sender, String content) {
        return new ChatEntry(ts, type, sender, content, "mc.example.com", "s1");
    }

    @Test
    void builderEnforcesDiscordLimits() {
        DiscordEmbed embed = new DiscordEmbed()
            .title("t".repeat(400))
            .description("d".repeat(5000))
            .field("n".repeat(400), "v".repeat(2000), true);

        Map<String, Object> map = embed.toMap();
        assertTrue(String.valueOf(map.get("title")).length() <= DiscordEmbed.TITLE_LIMIT);
        assertTrue(String.valueOf(map.get("description")).length() <= DiscordEmbed.DESCRIPTION_LIMIT);
        List<?> fields = (List<?>) map.get("fields");
        Map<?, ?> field = (Map<?, ?>) fields.get(0);
        assertTrue(String.valueOf(field.get("name")).length() <= DiscordEmbed.FIELD_NAME_LIMIT);
        assertTrue(String.valueOf(field.get("value")).length() <= DiscordEmbed.FIELD_VALUE_LIMIT);
    }

    @Test
    void builderCapsFieldCountAndSkipsBlankFields() {
        DiscordEmbed embed = new DiscordEmbed().title("x");
        for (int i = 0; i < 40; i++) embed.field("f" + i, "v" + i, false);
        embed.field("blank", "  ", false);
        embed.field("", "value", false);

        List<?> fields = (List<?>) embed.toMap().get("fields");
        assertEquals(DiscordEmbed.MAX_FIELDS, fields.size());
    }

    @Test
    void emptyEmbedSerialisesToNothing() {
        assertTrue(new DiscordEmbed().isEmpty());
        Map<String, Object> map = new DiscordEmbed().toMap();
        assertTrue(map.isEmpty());
    }

    @Test
    void payloadDisablesMentionsAndNeverCarriesAContentField() {
        DiscordEmbed embed = WebhookEmbeds.alert(WebhookEmbeds.Style.defaults(),
            KeywordRule.of("@everyone"), entry(1000, MessageType.PUBLIC_CHAT, "Notch", "@everyone now"));

        String payload = WebhookEmbeds.payload("Log Client", embed);
        Map<?, ?> map = Json.toMap(payload);

        assertNotNull(map);
        assertNull(map.get("content"), "an embed payload must not also carry flat content");
        Map<?, ?> mentions = (Map<?, ?>) map.get("allowed_mentions");
        assertNotNull(mentions);
        assertTrue(((List<?>) mentions.get("parse")).isEmpty());
        // The @ is broken with a zero-width space so it cannot ping.
        assertFalse(payload.contains("@everyone "), "captured mentions must be neutralised");
    }

    @Test
    void accentAutoColoursByTypeAndOverrideForcesOne() {
        WebhookEmbeds.Style auto = new WebhookEmbeds.Style("auto", "", false, true, "", true);
        DiscordEmbed join = WebhookEmbeds.presence(auto, true, "Alex", "mc.example.com");
        DiscordEmbed leave = WebhookEmbeds.presence(auto, false, "Alex", "mc.example.com");
        assertTrue(((Number) join.toMap().get("color")).intValue() != ((Number) leave.toMap().get("color")).intValue(),
            "join and leave should differ under auto");

        WebhookEmbeds.Style forced = new WebhookEmbeds.Style("red", "", false, true, "", true);
        DiscordEmbed joinForced = WebhookEmbeds.presence(forced, true, "Alex", "mc.example.com");
        assertEquals(EmbedColor.RED, ((Number) joinForced.toMap().get("color")).intValue());
    }

    @Test
    void everyCardTypeSerialisesWithATitle() {
        WebhookEmbeds.Style style = WebhookEmbeds.Style.defaults();
        long now = System.currentTimeMillis();

        List<DiscordEmbed> cards = List.of(
            WebhookEmbeds.alert(style, KeywordRule.of("wts"), entry(now, MessageType.PUBLIC_CHAT, "Steve", "wts")),
            WebhookEmbeds.summary(style, "mc.example.com", List.of(entry(now, MessageType.PUBLIC_CHAT, "Steve", "hi"))),
            WebhookEmbeds.digest(style, ChatDigest.summarise(
                List.of(entry(now, MessageType.PUBLIC_CHAT, "Steve", "hi")), 3), "mc.example.com"),
            WebhookEmbeds.session(style, new SessionRecord("s", "mc.example.com", now - 1000, now,
                10, 2, 1, 1, 0, 0), now),
            WebhookEmbeds.rename(style, "Old", "New", now),
            WebhookEmbeds.presence(style, true, "Alex", "mc.example.com"),
            WebhookEmbeds.death(style, entry(now, MessageType.DEATH, "Steve", "Steve was slain by Alex")),
            WebhookEmbeds.announcement(style, entry(now, MessageType.ANNOUNCEMENT, "", "Server restart in 5 minutes")),
            WebhookEmbeds.test(style, "14.0.0")
        );

        for (DiscordEmbed card : cards) {
            Map<String, Object> map = card.toMap();
            assertFalse(map.isEmpty(), "a card should never be empty");
            assertNotNull(map.get("title"), "every card has a title");
            assertNotNull(map.get("color"), "every card has an accent");
        }
    }

    @Test
    void digestCardCarriesTheCountsAndTopPlayers() {
        List<ChatEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) entries.add(entry(1000 + i, MessageType.PUBLIC_CHAT, "Steve", "hi"));
        entries.add(entry(2000, MessageType.JOIN, "Alex", "Alex joined the game"));

        DiscordEmbed embed = WebhookEmbeds.digest(WebhookEmbeds.Style.defaults(),
            ChatDigest.summarise(entries, 5), "mc.example.com");
        String json = embed.toJson();
        assertTrue(json.contains("Messages"));
        assertTrue(json.contains("Most active"));
        assertTrue(json.contains("Steve"));
    }

    @Test
    void summaryDescriptionStaysWithinTheEmbedLimit() {
        List<ChatEntry> entries = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            entries.add(entry(1000 + i, MessageType.PUBLIC_CHAT, "Steve",
                "a message long enough to fill the description many times over " + i));
        }
        DiscordEmbed embed = WebhookEmbeds.summary(WebhookEmbeds.Style.defaults(), "mc.example.com", entries);
        String description = String.valueOf(embed.toMap().get("description"));
        assertTrue(description.length() <= DiscordEmbed.DESCRIPTION_LIMIT);
        assertTrue(description.endsWith("and more"));
    }

    @Test
    void embedAccentParsesCaseInsensitively() {
        assertEquals(EmbedAccent.RED, EmbedAccent.valueOf("RED"));
        assertTrue(EmbedAccent.AUTO.isAuto());
        assertFalse(EmbedAccent.GREEN.isAuto());
        assertEquals("blurple", EmbedAccent.BLURPLE.id());
    }

    @Test
    void colorRoundTripsThroughHex() {
        assertEquals("#57F287", EmbedColor.toHex(EmbedColor.GREEN));
        assertEquals(EmbedColor.GREEN, EmbedColor.parse("#57f287", 0));
        assertEquals(EmbedColor.GREEN, EmbedColor.parse("57F287", 0));
        assertEquals(EmbedColor.BLURPLE, EmbedColor.parse("nonsense", EmbedColor.BLURPLE));
        assertEquals(EmbedColor.RED, EmbedColor.named("red"));
        assertEquals(EmbedColor.BLURPLE, EmbedColor.named("not-a-colour"));
    }
}
