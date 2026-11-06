package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.ChatFilterRule;
import com.logvex.addon.chatlog.core.ChatFilterRuleSet;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFilterTest {

    private static ChatEntry chat(String sender, String content) {
        return new ChatEntry(1000, MessageType.PUBLIC_CHAT, sender, content, "s", "s1");
    }

    @Test
    void emptySetHidesNothing() {
        ChatFilterRuleSet set = ChatFilterRuleSet.empty();
        assertTrue(set.isEmpty());
        assertFalse(set.hides(chat("Notch", "anything at all")));
        assertEquals(ChatFilterRule.Action.ALLOW, set.decide(chat("Notch", "hi")));
    }

    @Test
    void firstMatchWinsSoAllowCanOverrideHide() {
        // A specific ALLOW is placed above a broad HIDE, which is the documented way to punch a hole.
        List<ChatFilterRule> rules = List.of(
            new ChatFilterRule("diamond ore", "", null, ChatFilterRule.Action.ALLOW, false, false, true),
            ChatFilterRule.hide("diamond")
        );
        ChatFilterRuleSet set = ChatFilterRuleSet.of(rules, ChatFilterRule.Action.ALLOW);

        assertFalse(set.hides(chat("Notch", "diamond ore for sale")));
        assertTrue(set.hides(chat("Notch", "selling diamond here")));
    }

    @Test
    void orderIsSignificantSoABroadRuleFirstShadowsASpecificOne() {
        // With the broad HIDE first, it wins before the ALLOW is ever consulted.
        List<ChatFilterRule> rules = List.of(
            ChatFilterRule.hide("diamond"),
            new ChatFilterRule("diamond ore", "", null, ChatFilterRule.Action.ALLOW, false, false, true)
        );
        ChatFilterRuleSet set = ChatFilterRuleSet.of(rules, ChatFilterRule.Action.ALLOW);
        assertTrue(set.hides(chat("Notch", "diamond ore for sale")));
    }

    @Test
    void defaultActionAppliesWhenNoRuleMatches() {
        ChatFilterRuleSet hideAll = ChatFilterRuleSet.of(List.of(), ChatFilterRule.Action.HIDE);
        assertTrue(hideAll.hides(chat("Notch", "hello")));

        List<ChatFilterRule> allowAnnouncements = List.of(
            new ChatFilterRule("", "", MessageType.ANNOUNCEMENT, ChatFilterRule.Action.ALLOW, false, false, true)
        );
        ChatFilterRuleSet set = ChatFilterRuleSet.of(allowAnnouncements, ChatFilterRule.Action.HIDE);
        assertFalse(set.hides(new ChatEntry(1, MessageType.ANNOUNCEMENT, "", "server restart", "", "")));
        assertTrue(set.hides(chat("Notch", "hello")));
    }

    @Test
    void playerRuleMatchesOnlyThatSender() {
        ChatFilterRuleSet set = ChatFilterRuleSet.of(
            List.of(ChatFilterRule.hidePlayer("spammer")), ChatFilterRule.Action.ALLOW);
        assertTrue(set.hides(chat("spammer", "buy gold")));
        assertFalse(set.hides(chat("Notch", "buy gold")));
        // Sender matching is case-insensitive.
        assertTrue(set.hides(chat("SPAMMER", "anything")));
    }

    @Test
    void wholeWordMatchingDoesNotMatchSubstrings() {
        ChatFilterRule whole = new ChatFilterRule("base", "", null,
            ChatFilterRule.Action.HIDE, false, true, true);
        ChatFilterRuleSet set = ChatFilterRuleSet.of(List.of(whole), ChatFilterRule.Action.ALLOW);

        assertTrue(set.hides(chat("Notch", "my base is here")));
        assertFalse(set.hides(chat("Notch", "the database is down")));
        assertTrue(set.hides(chat("Notch", "base-1 coords")));
    }

    @Test
    void caseSensitiveRuleHonoursCase() {
        ChatFilterRule sensitive = new ChatFilterRule("WTS", "", null,
            ChatFilterRule.Action.HIDE, true, false, true);
        ChatFilterRuleSet set = ChatFilterRuleSet.of(List.of(sensitive), ChatFilterRule.Action.ALLOW);
        assertTrue(set.hides(chat("Notch", "WTS diamonds")));
        assertFalse(set.hides(chat("Notch", "wts diamonds")));
    }

    @Test
    void disabledRulesAreDropped() {
        ChatFilterRule disabled = new ChatFilterRule("diamond", "", null,
            ChatFilterRule.Action.HIDE, false, false, false);
        ChatFilterRuleSet set = ChatFilterRuleSet.of(List.of(disabled), ChatFilterRule.Action.ALLOW);
        assertTrue(set.rules().isEmpty());
        assertFalse(set.hides(chat("Notch", "diamond")));
    }

    @Test
    void encodeDecodeRoundTripsEveryField() {
        ChatFilterRule original = new ChatFilterRule("diamond", "Notch", MessageType.PUBLIC_CHAT,
            ChatFilterRule.Action.HIDE, true, true, true);
        ChatFilterRule decoded = ChatFilterRule.decode(original.encode());
        assertNotNull(decoded);
        assertEquals(original, decoded);
    }

    @Test
    void decodeIgnoresUnknownKeysFromNewerBuilds() {
        ChatFilterRule decoded = ChatFilterRule.decode("gold;sender=Alex;future=yes;action=allow");
        assertNotNull(decoded);
        assertEquals("gold", decoded.pattern());
        assertEquals("Alex", decoded.sender());
        assertEquals(ChatFilterRule.Action.ALLOW, decoded.action());
    }

    @Test
    void decodeRejectsEmptyAndCatchAllRules() {
        assertNull(ChatFilterRule.decode(null));
        assertNull(ChatFilterRule.decode("   "));
        assertNull(ChatFilterRule.decode(";sender=;type="));
    }

    @Test
    void nullEntriesAreNeverHidden() {
        ChatFilterRuleSet set = ChatFilterRuleSet.of(
            List.of(ChatFilterRule.hide("x")), ChatFilterRule.Action.HIDE);
        assertFalse(set.hides(null));
        assertEquals(ChatFilterRule.Action.ALLOW, set.decide(null));
    }
}
