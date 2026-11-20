package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.ChatActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code !player <name>} composes what the client already knows into one card. */
class PlayerDossierTest {

    private static AssistantBrain.Decision player(String query, AssistantMemory memory,
                                                  ChatActivity activity) {
        AssistantBrain brain = new AssistantBrain(memory);
        return brain.command(com.logvex.addon.assistant.core.SenderIdentity.local("Alex", null),
            "!player " + query, AssistantConfig.DEFAULTS, System.currentTimeMillis(),
            com.logvex.addon.stats.ServerStats.OFFLINE, null, null, activity);
    }

    @Test
    @DisplayName("remembered facts are shown")
    void showsFacts() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberPlayerFact("Steve", "rank", "admin", 1L);
        AssistantBrain.Decision decision = player("Steve", memory, new ChatActivity());
        assertTrue(decision.hasEmbed());
        assertTrue(decision.text().contains("admin") || decision.lines(48,
            com.logvex.addon.assistant.core.ReplyFormat.Style.BOX).toString().contains("admin"));
    }

    @Test
    @DisplayName("observed activity is shown as a count, not a guess")
    void showsActivity() {
        AssistantMemory memory = new AssistantMemory();
        ChatActivity activity = new ChatActivity();
        activity.observe("Steve", 1L);
        activity.observe("Steve", 2L);
        AssistantBrain.Decision decision = player("Steve", memory, activity);
        assertTrue(decision.hasEmbed());
        assertTrue(decision.lines(48, com.logvex.addon.assistant.core.ReplyFormat.Style.BOX)
            .toString().contains("2"));
    }

    @Test
    @DisplayName("an unknown player is said to be unknown, not shown as an empty card")
    void unknownPlayer() {
        AssistantBrain.Decision decision = player("Nobody", new AssistantMemory(), new ChatActivity());
        assertFalse(decision.hasEmbed(), "an empty card would imply data that does not exist");
        assertTrue(decision.text().contains("know nothing"));
    }

    @Test
    @DisplayName("a missing name explains the usage")
    void missingName() {
        AssistantBrain.Decision decision = player("", new AssistantMemory(), new ChatActivity());
        assertTrue(decision.text().contains("Usage"));
    }

    @Test
    @DisplayName("a stranger cannot read the operator's notes on a player")
    void playerIsLocalOnly() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberPlayerFact("Steve", "rank", "private note", 1L);
        AssistantBrain brain = new AssistantBrain(memory);
        AssistantBrain.Decision decision = brain.command(
            com.logvex.addon.assistant.core.SenderIdentity.unverified("Stranger"),
            "!player Steve", AssistantConfig.DEFAULTS, System.currentTimeMillis(),
            com.logvex.addon.stats.ServerStats.OFFLINE, null, null, new ChatActivity());
        assertFalse(decision.hasEmbed());
        assertFalse(decision.text().contains("private note"));
    }

    @Test
    @DisplayName("the player lookup is case-insensitive on facts")
    void caseInsensitive() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberPlayerFact("Steve", "rank", "admin", 1L);
        assertTrue(player("steve", memory, new ChatActivity()).hasEmbed());
    }
}
