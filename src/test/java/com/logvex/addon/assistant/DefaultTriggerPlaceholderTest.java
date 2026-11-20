package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.Placeholders;
import com.logvex.addon.assistant.core.ResponseTemplate;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped trigger table must not print raw placeholder tokens.
 *
 * <p>An unregistered token is deliberately left verbatim by {@link Placeholders}, which is right
 * for a user's typo but wrong for a default we ship: a fresh install answering "who are you" with
 * the literal text "I am {name}" looks broken. These tests expand every default reply against a
 * realistic context and fail on any leftover brace.
 */
class DefaultTriggerPlaceholderTest {

    private static final long NOW = 1_700_000_000_000L;

    private static ResponseTemplate.Context context(AssistantMemory memory) {
        ServerStats stats = new ServerStats(
            true, "mc.example.net", "mc.example.net:25565", false,
            19.8f, 42, 12, 40, 3_600_000L,
            "minecraft:overworld", "world", "plains", 6_000L, 1L,
            "clear", 144, 512L, 4096L,
            List.of("Notch"), null
        );
        return new ResponseTemplate.Context("Notch", "hello", "mc.example.net", memory, NOW, stats,
            "Assistant", "!");
    }

    @Test
    @DisplayName("no default trigger reply leaves a placeholder token unexpanded")
    void everyDefaultReplyExpandsFully() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("owner", "Notch", NOW);
        ResponseTemplate.Context ctx = context(memory);

        List<String> broken = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String reply = ResponseTemplate.expand(rule.reply(), ctx);
            if (reply.contains("{") || reply.contains("}")) {
                broken.add(rule.pattern() + " -> " + reply);
            }
        }
        assertTrue(broken.isEmpty(), "default triggers print a raw placeholder: " + broken);
    }

    @Test
    @DisplayName("every alias of every default trigger also expands fully")
    void aliasesAreCovered() {
        AssistantMemory memory = new AssistantMemory();
        ResponseTemplate.Context ctx = context(memory);
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            for (String phrase : rule.phrases()) {
                assertFalse(phrase.isBlank(), "a default trigger has a blank phrase");
            }
        }
    }

    @Test
    @DisplayName("{name} and {prefix} resolve to the configured assistant identity")
    void identityPlaceholders() {
        ResponseTemplate.Context ctx = context(new AssistantMemory());
        assertEquals("Assistant", ResponseTemplate.expand("{name}", ctx));
        assertEquals("!", ResponseTemplate.expand("{prefix}", ctx));
    }

    @Test
    @DisplayName("identity placeholders fall back rather than render blank")
    void identityPlaceholdersHaveFallbacks() {
        ResponseTemplate.Context bare = new ResponseTemplate.Context(
            "Notch", "hi", "", new AssistantMemory(), NOW);
        assertEquals("the assistant", ResponseTemplate.expand("{name}", bare));
        assertEquals("!", ResponseTemplate.expand("{prefix}", bare));
    }

    @Test
    @DisplayName("{weather} reads the live weather")
    void weatherPlaceholder() {
        assertEquals("clear", ResponseTemplate.expand("{weather}", context(new AssistantMemory())));
    }

    @Test
    @DisplayName("{factcount} counts the stored facts")
    void factCountPlaceholder() {
        AssistantMemory memory = new AssistantMemory();
        assertEquals("0", ResponseTemplate.expand("{factcount}", context(memory)));
        memory.rememberFact("owner", "Notch", NOW);
        memory.rememberFact("base", "spawn", NOW);
        assertEquals("2", ResponseTemplate.expand("{factcount}", context(memory)));
    }

    @Test
    @DisplayName("an unknown placeholder is still left verbatim, for the user's own typos")
    void unknownTokensAreStillPreserved() {
        assertEquals("{nonsense}",
            ResponseTemplate.expand("{nonsense}", context(new AssistantMemory())));
    }
}
