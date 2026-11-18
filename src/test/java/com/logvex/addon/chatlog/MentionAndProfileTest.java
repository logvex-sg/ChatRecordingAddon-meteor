package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DiscordEmbed;
import com.logvex.addon.chatlog.core.Json;
import com.logvex.addon.chatlog.core.MentionScanner;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import com.logvex.addon.chatlog.core.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mention scanner and the new embed cards (mention, leaderboard, profile). These pin the
 * word-boundary behaviour of mention matching and the shape of the added cards, so a busy server
 * cannot produce a mention hit inside a longer word or a card with no title.
 */
class MentionAndProfileTest {

    private static ChatEntry entry(String sender, String content) {
        return new ChatEntry(1000L, MessageType.PUBLIC_CHAT, sender, content, "mc.example.com", "s1");
    }

    @Test
    void mentionMatchesWholeWordsOnly() {
        assertTrue(MentionScanner.contains("hey Steve, are you there?", "Steve"));
        assertFalse(MentionScanner.contains("SteveJobs was here", "Steve"),
            "a name inside a longer word must not match");
        assertFalse(MentionScanner.contains("ping Steve_99 now", "Steve"),
            "an underscore is part of a name, so Steve_99 is a different player");
        assertTrue(MentionScanner.contains("Steve, look", "steve"), "matching is case-insensitive");
    }

    @Test
    void findReturnsEachNameOnceAndSkipsTheSender() {
        List<String> hits = MentionScanner.find(List.of("Steve", "Alex", "Steve"),
            entry("Notch", "Steve and Steve and Alex are here"));
        assertEquals(List.of("Steve", "Alex"), hits);

        // A player mentioning their own name is not a mention of interest.
        assertTrue(MentionScanner.find(List.of("Notch"), entry("Notch", "Notch is here")).isEmpty());
    }

    @Test
    void findIgnoresBlankNames() {
        assertTrue(MentionScanner.find(List.of("", "  "), entry("Notch", "hello")).isEmpty());
        assertTrue(MentionScanner.find(null, entry("Notch", "hello")).isEmpty());
        assertTrue(MentionScanner.find(List.of("Steve"), null).isEmpty());
    }

    @Test
    void mentionCardCarriesTheNamesAndSender() {
        DiscordEmbed embed = WebhookEmbeds.mention(WebhookEmbeds.Style.defaults(),
            List.of("Steve", "Alex"), entry("Notch", "hey Steve and Alex"));
        Map<String, Object> map = embed.toMap();
        assertNotNull(map.get("title"));
        assertTrue(String.valueOf(map.get("title")).contains("Mention"));
        String json = embed.toJson();
        assertTrue(json.contains("Steve"));
        assertTrue(json.contains("Notch"));
    }

    @Test
    void leaderboardRanksInOrderAndNumbersFromOne() {
        DiscordEmbed embed = WebhookEmbeds.leaderboard(WebhookEmbeds.Style.defaults(),
            List.of(new ChatDigest.PlayerLine("Steve", 128, 0, 0, 0),
                new ChatDigest.PlayerLine("Alex", 96, 0, 0, 0)),
            "mc.example.com");
        String description = String.valueOf(embed.toMap().get("description"));
        assertTrue(description.contains("**1.** Steve"));
        assertTrue(description.contains("**2.** Alex"));
        assertTrue(description.indexOf("Steve") < description.indexOf("Alex"));
    }

    @Test
    void profileCardCarriesTheObservedCounts() {
        PlayerActivity activity = new PlayerActivity("Steve");
        ChatEntry join = new ChatEntry(1000L, MessageType.JOIN, "Steve", "Steve joined", "s", "s1");
        ChatEntry chat = new ChatEntry(2000L, MessageType.PUBLIC_CHAT, "Steve", "hi", "s", "s1");
        ChatEntry death = new ChatEntry(3000L, MessageType.DEATH, "Steve", "Steve died", "s", "s1");
        // observe() is package-private to the core package; rebuild through the public tracker path.
        activity = observed("Steve", List.of(join, chat, death));

        DiscordEmbed embed = WebhookEmbeds.profile(WebhookEmbeds.Style.defaults(), activity, "mc.example.com");
        Map<String, Object> map = embed.toMap();
        assertNotNull(map.get("title"));
        String json = embed.toJson();
        assertTrue(json.contains("Messages"));
        assertTrue(json.contains("Deaths"));
    }

    private static PlayerActivity observed(String name, List<ChatEntry> entries) {
        com.logvex.addon.chatlog.core.PlayerTracker tracker =
            new com.logvex.addon.chatlog.core.PlayerTracker();
        for (ChatEntry entry : entries) tracker.record(entry);
        return tracker.get(name);
    }

    @Test
    void newCardsSerialiseAsEmbedPayloadsWithoutContent() {
        WebhookEmbeds.Style style = WebhookEmbeds.Style.defaults();
        List<String> payloads = List.of(
            WebhookPayload.mention("Log Client", List.of("Steve"), entry("Notch", "hey Steve"), style),
            WebhookPayload.leaderboard("Log Client",
                List.of(new ChatDigest.PlayerLine("Steve", 10, 0, 0, 0)), "mc.example.com", style),
            WebhookPayload.profile("Log Client", observed("Steve", List.of(entry("Steve", "hi"))),
                "mc.example.com", style)
        );
        for (String payload : payloads) {
            Map<?, ?> map = Json.toMap(payload);
            assertNotNull(map);
            assertFalse(map.containsKey("content"), "a card must not also carry flat content");
            assertNotNull(map.get("embeds"));
            assertTrue(((List<?>) map.get("embeds")).size() == 1);
        }
    }
}
