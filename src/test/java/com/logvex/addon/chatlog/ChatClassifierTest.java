package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatClassifier;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatClassifierTest {

    @Test
    void classifiesPlayerChat() {
        ChatClassifier.Result result = ChatClassifier.classify(true, "<Notch> hello world");
        assertEquals(MessageType.PUBLIC_CHAT, result.type());
        assertEquals("Notch", result.sender());
    }

    @Test
    void classifiesPlayerChatWithoutBrackets() {
        ChatClassifier.Result result = ChatClassifier.classify(true, "Alex: hi there");
        assertEquals(MessageType.PUBLIC_CHAT, result.type());
        assertEquals("Alex", result.sender());
    }

    @Test
    void classifiesDirectMessage() {
        ChatClassifier.Result result = ChatClassifier.classify(true, "Steve whispers to you: secret");
        assertEquals(MessageType.PLAYER_MESSAGE, result.type());
        assertEquals("Steve", result.sender());
    }

    @Test
    void classifiesJoin() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "Herobrine joined the game");
        assertEquals(MessageType.JOIN, result.type());
        assertEquals("Herobrine", result.sender());
    }

    @Test
    void classifiesLeave() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "Herobrine left the game");
        assertEquals(MessageType.LEAVE, result.type());
        assertEquals("Herobrine", result.sender());
    }

    @Test
    void classifiesDeath() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "Notch was slain by Herobrine");
        assertEquals(MessageType.DEATH, result.type());
    }

    @Test
    void classifiesAdvancementAsGame() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "Alex has made the advancement [Stone Age]");
        assertEquals(MessageType.GAME, result.type());
    }

    @Test
    void classifiesServerBroadcastAsAnnouncement() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "[Server] Restart in 5 minutes");
        assertEquals(MessageType.ANNOUNCEMENT, result.type());
    }

    @Test
    void classifiesBracketedLineAsSystem() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "[Rank] Some unrelated message");
        assertEquals(MessageType.SYSTEM, result.type());
    }

    @Test
    void stripsTimestampBeforeClassifying() {
        ChatClassifier.Result result = ChatClassifier.classify(false, "[12:34:56] Herobrine joined the game");
        assertEquals(MessageType.JOIN, result.type());
        assertEquals("Herobrine", result.sender());
    }

    @Test
    void emptyInputIsOther() {
        assertEquals(MessageType.OTHER, ChatClassifier.classify(false, "   ").type());
    }

    @Test
    void extractSenderReturnsEmptyForPlainText() {
        assertEquals("", ChatClassifier.extractSender("just some text"));
    }
}
