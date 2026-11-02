package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.KeywordMatcher;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRuleTest {

    private static ChatEntry entry(String content) {
        return new ChatEntry(1L, MessageType.PUBLIC_CHAT, "Notch", content, "s", "s1");
    }

    @Test
    void simpleMatchIsCaseInsensitiveByDefault() {
        KeywordRule rule = KeywordRule.of("wts");
        assertTrue(KeywordMatcher.matches(rule, entry("WTS diamonds")));
        assertFalse(KeywordMatcher.matches(rule, entry("nothing here")));
    }

    @Test
    void caseSensitiveRuleRespectsCase() {
        KeywordRule rule = new KeywordRule("WTS", true, false, false, false, true, 0);
        assertTrue(KeywordMatcher.matches(rule, entry("WTS stuff")));
        assertFalse(KeywordMatcher.matches(rule, entry("wts stuff")));
    }

    @Test
    void wholeWordDoesNotMatchSubstring() {
        KeywordRule rule = new KeywordRule("base", false, true, false, false, true, 0);
        assertTrue(KeywordMatcher.matches(rule, entry("my base is here")));
        assertFalse(KeywordMatcher.matches(rule, entry("database")));
        assertTrue(KeywordMatcher.matches(rule, entry("base-1 location")));
    }

    @Test
    void matchesSenderToo() {
        KeywordRule rule = KeywordRule.of("Notch");
        assertTrue(KeywordMatcher.matches(rule, entry("hello")));
    }

    @Test
    void disabledRuleNeverMatches() {
        KeywordRule rule = new KeywordRule("wts", false, false, false, false, false, 0);
        // The enabled flag is applied by the collection overload, which is what the modules use.
        assertTrue(KeywordMatcher.matches(List.of(rule), entry("wts")).isEmpty());
        assertFalse(KeywordMatcher.anySound(List.of(rule), entry("wts")));
        assertFalse(KeywordMatcher.anyWebhook(List.of(rule), entry("wts")));
    }

    @Test
    void anySoundAndAnyWebhookAggregate() {
        KeywordRule sound = new KeywordRule("wts", false, false, true, false, true, 0);
        KeywordRule hook = new KeywordRule("wts", false, false, false, true, true, 0);
        assertTrue(KeywordMatcher.anySound(List.of(sound, hook), entry("wts")));
        assertTrue(KeywordMatcher.anyWebhook(List.of(sound, hook), entry("wts")));
    }

    @Test
    void encodeDecodeRoundTrip() {
        KeywordRule original = new KeywordRule("event", true, true, false, true, false, 0x123456);
        KeywordRule decoded = KeywordRule.decode(original.encode());
        assertEquals(original, decoded);
    }

    @Test
    void decodeIgnoresUnknownKeys() {
        KeywordRule decoded = KeywordRule.decode("wts;case=true;future=whatever");
        assertEquals("wts", decoded.pattern());
        assertTrue(decoded.caseSensitive());
    }

    @Test
    void decodeRejectsBlank() {
        assertNull(KeywordRule.decode("   "));
        assertNull(KeywordRule.decode(";case=true"));
    }

    @Test
    void defaultsAreUsable() {
        List<KeywordRule> defaults = KeywordRule.defaults();
        assertFalse(defaults.isEmpty());
        for (KeywordRule rule : defaults) {
            assertFalse(rule.pattern().isBlank());
        }
    }
}
