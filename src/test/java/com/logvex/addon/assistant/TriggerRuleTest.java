package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trigger rules: encoding round-trips, matching options and sender filtering. */
class TriggerRuleTest {

    @Test
    @DisplayName("a rule survives encode and decode with every field intact")
    void encodesAndDecodes() {
        TriggerRule rule = new TriggerRule("hello", true, true, "Notch", "Hi {player}",
            TriggerRule.ReplyMode.NOTIFY, 5000, 3, true, true, false);

        TriggerRule decoded = TriggerRule.decode(rule.encode());
        assertNotNull(decoded);
        assertEquals("hello", decoded.pattern());
        assertTrue(decoded.caseSensitive());
        assertTrue(decoded.wholeWord());
        assertEquals("Notch", decoded.sender());
        assertEquals("Hi {player}", decoded.reply());
        assertEquals(TriggerRule.ReplyMode.NOTIFY, decoded.mode());
        assertEquals(5000, decoded.cooldownMs());
        assertEquals(3, decoded.sessionLimit());
        assertTrue(decoded.sound());
        assertTrue(decoded.remember());
        assertFalse(decoded.enabled());
    }

    @Test
    @DisplayName("a rule with no reply and unknown keys still decodes")
    void toleratesUnknownKeys() {
        TriggerRule decoded = TriggerRule.decode("ping;reply=pong;future=whatever;case=true");
        assertNotNull(decoded);
        assertEquals("ping", decoded.pattern());
        assertEquals("pong", decoded.reply());
        assertTrue(decoded.caseSensitive());
    }

    @Test
    @DisplayName("a blank encoded rule decodes to null")
    void rejectsBlank() {
        assertNull(TriggerRule.decode(""));
        assertNull(TriggerRule.decode("  "));
        assertNull(TriggerRule.decode(null));
        assertNull(TriggerRule.decode(";case=true"));
    }

    @Test
    @DisplayName("case-insensitive matching is the default")
    void matchesCaseInsensitivelyByDefault() {
        TriggerRule rule = TriggerRule.of("Hello Bot", "hi");
        assertTrue(TriggerSet.matches(rule, "hello bot there"));
        assertFalse(TriggerSet.matches(rule, "hello there"));
    }

    @Test
    @DisplayName("whole-word matching does not fire inside a larger word")
    void wholeWordMatching() {
        TriggerRule rule = new TriggerRule("cat", false, true, "", "meow",
            TriggerRule.ReplyMode.CHAT, 0, 0, false, false, true);
        assertTrue(TriggerSet.matches(rule, "a cat sat"));
        assertFalse(TriggerSet.matches(rule, "concatenate"));
        assertFalse(TriggerSet.matches(rule, "category"));
    }

    @Test
    @DisplayName("a sender filter limits who can trigger the rule")
    void senderFilter() {
        TriggerRule rule = new TriggerRule("hi", false, false, "Notch", "hello",
            TriggerRule.ReplyMode.CHAT, 0, 0, false, false, true);
        assertTrue(rule.senderAllowed("notch"));
        assertFalse(rule.senderAllowed("Herobrine"));
        assertFalse(rule.senderAllowed(""));

        TriggerSet set = TriggerSet.of(List.of(rule));
        assertNotNull(set.firstMatch("Notch", "hi"));
        assertNull(set.firstMatch("Herobrine", "hi"));
    }

    @Test
    @DisplayName("the first matching rule wins and disabled rules never match")
    void firstMatchWins() {
        TriggerRule first = TriggerRule.of("hi", "first");
        TriggerRule second = TriggerRule.of("hi", "second");
        TriggerRule disabled = new TriggerRule("hi", false, false, "", "off",
            TriggerRule.ReplyMode.CHAT, 0, 0, false, false, false);

        TriggerSet set = TriggerSet.of(List.of(disabled, first, second));
        assertEquals("first", set.firstMatch("", "hi").reply());
    }

    @Test
    @DisplayName("a blank message or blank pattern never matches")
    void blankInputsNeverMatch() {
        TriggerSet set = TriggerSet.of(List.of(TriggerRule.of("hi", "hello")));
        assertNull(set.firstMatch("", ""));
        assertNull(set.firstMatch("", "   "));
        assertNull(set.firstMatch("", null));
        assertNull(set.firstMatch("", "xyzzy plugh"));
        assertFalse(set.isEmpty());
        assertTrue(TriggerSet.of(List.of(TriggerRule.of("  ", "x"))).isEmpty());
    }
}
