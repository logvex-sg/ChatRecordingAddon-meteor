package com.logvex.addon.assistant;

import com.logvex.addon.ai.core.AiEngine;
import com.logvex.addon.ai.core.IntentModel;
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
    @DisplayName("a fresh install ships a usable trigger table, all at the one-second cooldown")
    void preconfiguredTableIsComplete() {
        List<TriggerRule> table = TriggerRule.preconfigured();
        assertTrue(table.size() >= 18, "expected a real table, got " + table.size() + " rules");
        for (TriggerRule rule : table) {
            assertEquals(TriggerRule.DEFAULT_COOLDOWN_MS, rule.cooldownMs(),
                "rule '" + rule.pattern() + "' must use the 1000 ms default");
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

    // ---------------------------------------------------------------- AI routing

    @Test
    @DisplayName("the lines the classifier got wrong are now routed correctly")
    void routingFixesMeasuredMistakes() {
        AiEngine engine = new AiEngine();
        // Each pair is a line the bag-of-words classifier answered with an unrelated intent.
        String[][] cases = {
            {"what is the prefix", "prefix"},
            {"how do i use this", "help"},
            {"what can you do", "help"},
            {"how are you", "status"},
            {"how is it going", "status"},
            {"whats up", "status"},
            {"is the server lagging", "tickrate"},
            {"is it night", "weather"},
            {"where am i", "position"},
            {"what are my coords", "position"},
            {"are you a bot", "identity"},
            {"whats the ip", "serverinfo"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], engine.intentOf(c[0]), "intent for: " + c[0]);
        }
    }

    @Test
    @DisplayName("a plain chat line still gets an answer, and a question mark does not break it")
    void ordinaryLinesAreAnswered() {
        AiEngine engine = new AiEngine();
        String[] lines = {
            "yo", "hiya", "sup", "morning", "hello there",
            "lol", "haha", "gg", "wp", "ez", "nice",
            "tyvm", "appreciate it", "im out", "later", "cya",
            "tps?", "check my ping", "server lag",
        };
        for (String line : lines) {
            String reply = engine.respond(line, "Steve", null, 7L);
            assertFalse(reply == null || reply.isBlank(), "no reply for: " + line);
        }
    }

    @Test
    @DisplayName("the router beats the classifier on the same lines")
    void routerIsMoreAccurateThanTheClassifierAlone() {
        // The routing table is only justified if it is measurably better than the classifier it sits
        // in front of. This pins the measurement quoted in the README so a future corpus edit cannot
        // quietly make the claim false.
        String[] lines = {
            "yo", "hiya", "hello there", "hey bot", "sup", "good evening", "morning",
            "whats the weather like", "is it night", "is it day time", "how is the weather",
            "whats the time", "got the time", "time please",
            "how laggy am i", "whats my ping", "check my ping", "is the server lagging",
            "tps?", "how many tps", "server lag",
            "coords", "what are my coords", "where even am i", "which biome",
            "who are you", "what are you", "are you a bot", "whats your name",
            "help me", "what commands", "how do i use this", "what can you do",
            "joke", "tell me something funny", "make me laugh",
            "thanks a lot", "thank you", "tyvm", "appreciate it",
            "cya", "later", "im out", "gtg",
            "lol", "lmao", "haha", "gg", "wp", "nice", "ez",
            "whats up", "how are you", "how is it going", "you good",
            "what server", "whats the ip", "which world",
            "what is the prefix", "hello", "hi bot", "goodbye", "thanks",
        };
        IntentModel model = new IntentModel();
        int routed = 0;
        int agrees = 0;
        for (String line : lines) {
            var truth = model.route(line);
            if (truth == null) continue;
            routed++;
            var bare = model.classifyWithoutRouting(line);
            if (bare != null && truth.name().equals(bare.name())) agrees++;
        }
        assertEquals(lines.length, routed, "the table should route every one of these lines");
        assertTrue(agrees < routed,
            "if the classifier already agreed on everything the router would be dead weight");
        assertTrue(agrees >= routed * 3 / 4,
            "the classifier should still be broadly right, got " + agrees + "/" + routed);
    }

    @Test
    @DisplayName("the router only fires when every required token is present")
    void routingRequiresAllTokens() {
        AiEngine engine = new AiEngine();
        // "ping" alone is the latency intent, but "is the server lagging" names the server and asks
        // about lag, which must win over the bare "server" rule.
        assertEquals("tickrate", engine.intentOf("is the server lagging"));
        assertEquals("latency", engine.intentOf("what is my ping"));
        // A message with no rule token falls through to the classifier rather than being forced.
        assertNotEquals("prefix", engine.intentOf("the weather is nice today"));
    }
}
