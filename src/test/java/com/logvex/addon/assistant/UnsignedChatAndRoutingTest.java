package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.chatlog.core.ChatClassifier;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three defects behind "it only works half the time" and "the AI is still stupid".
 *
 * <p>Each test here is a reproduction, not a restatement of the implementation: the unsigned-chat
 * case is the exact shape a proxy sends, the routing cases are lines the classifier measurably got
 * wrong, and the table cases are what a fresh install has to answer without any setup.
 */
class UnsignedChatAndRoutingTest {

    // ---------------------------------------------------------------- chat shapes

    @Test
    @DisplayName("chat without a signed-chat indicator is still recognised as a player talking")
    void unsignedChatIsPlayerChat() {
        // Minecraft attaches a chat indicator only to signed chat. A proxy, an offline-mode server
        // or a chat plugin sends these instead, and classifying them as SYSTEM meant a default
        // install (watch-system off) silently dropped every player message.
        String[][] cases = {
            {"<Steve> hello bot", "Steve"},
            {"Steve: hello bot", "Steve"},
            {"[MVP+] Steve: hello bot", "Steve"},
            {"[VIP] <Steve> hello bot", "Steve"},
        };
        for (String[] c : cases) {
            ChatClassifier.Result result = ChatClassifier.classify(false, c[0]);
            assertEquals(MessageType.PUBLIC_CHAT, result.type(), "type for: " + c[0]);
            assertEquals(c[1], result.sender(), "sender for: " + c[0]);
        }
    }

    @Test
    @DisplayName("system lines that merely look like chat are not mistaken for a player")
    void systemLinesStaySystem() {
        String[] lines = {
            "Server: restarting in 5",
            "Announcement: event starting",
            "Note: this is fine",
            "12:34",
            "[Server] Restart in 5 minutes",
            "[Rank] Some unrelated message",
        };
        for (String line : lines) {
            MessageType type = ChatClassifier.classify(false, line).type();
            assertNotEquals(MessageType.PUBLIC_CHAT, type, "misread as chat: " + line);
        }
    }

    @Test
    @DisplayName("join, leave and death lines keep their own type")
    void nonChatTypesAreUnchanged() {
        assertEquals(MessageType.JOIN, ChatClassifier.classify(false, "Steve joined the game").type());
        assertEquals(MessageType.LEAVE, ChatClassifier.classify(false, "Steve left the game").type());
        assertEquals(MessageType.DEATH, ChatClassifier.classify(false, "Steve was slain by Alex").type());
        assertEquals(MessageType.GAME,
            ChatClassifier.classify(false, "Alex has made the advancement [Stone Age]").type());
    }

    // ---------------------------------------------------------------- trigger table

    @Test
    @DisplayName("a fresh install ships a usable trigger table, all at the shipped cooldown")
    void preconfiguredTableIsComplete() {
        List<TriggerRule> table = TriggerRule.preconfigured();
        assertTrue(table.size() >= 18, "expected a real table, got " + table.size() + " rules");
        for (TriggerRule rule : table) {
            assertEquals(TriggerRule.TRIGGER_COOLDOWN_MS, rule.cooldownMs(),
                "rule '" + rule.pattern() + "' must use the 3000 ms shipped cooldown");
            assertTrue(rule.enabled(), "rule '" + rule.pattern() + "' must ship enabled");
            assertFalse(rule.reply().isBlank(), "rule '" + rule.pattern() + "' has no reply");
            assertTrue(rule.wholeWord(), "rule '" + rule.pattern() + "' must match whole words");
        }
    }

    @Test
    @DisplayName("the shipped table answers ordinary chat, and whole words stop false hits")
    void preconfiguredTableMatchesRealLines() {
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());

        assertNotNull(set.firstMatch("Steve", "hello everyone"), "'hello' should match");
        assertNotNull(set.firstMatch("Steve", "thanks!"), "the 'thank you' alias should match");
        assertNotNull(set.firstMatch("Steve", "where am i"), "'where am i' should match");
        assertNotNull(set.firstMatch("Steve", "gg"), "'gg' should match");

        // "hi" is not a rule precisely so it cannot fire inside "this" / "which".
        assertEquals(null, set.firstMatch("Steve", "this is a which test"),
            "no rule may fire inside an unrelated word");
    }

    @Test
    @DisplayName("no shipped rule pattern contains another, so order never decides the answer")
    void preconfiguredPatternsDoNotNest() {
        List<TriggerRule> table = TriggerRule.preconfigured();
        for (TriggerRule outer : table) {
            for (TriggerRule inner : table) {
                if (outer == inner) continue;
                assertFalse(outer.pattern().contains(inner.pattern()),
                    "'" + outer.pattern() + "' contains '" + inner.pattern()
                        + "', so first-match-wins would pick by accident");
            }
        }
    }
}
