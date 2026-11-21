package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !help <command>} used to be read as "page 1", because any text with no number fell through
 * to the pager. Asking about one command now answers with that command, and paging still works.
 */
class HelpDetailTest {

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    @Test
    @DisplayName("!help <name> answers about that command, not the whole list")
    void namedCommandGetsItsOwnCard() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!help modcall", 1_000L);

        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed());
        assertEquals("!modcall", decision.embed().title());
    }

    @Test
    @DisplayName("the card carries the usage, the description and the access level")
    void cardCarriesTheFacts() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!help macros", 1_000L);
        String rendered = String.join(" ", decision.lines(60, null));

        assertTrue(rendered.contains("!macros"), rendered);
        assertTrue(rendered.contains("macro"), rendered);
        assertTrue(rendered.contains("Everyone"), rendered);
    }

    @Test
    @DisplayName("an alias resolves to the same card")
    void aliasResolves() {
        AssistantBrain.Decision viaAlias = runtime().handle("Notch", "!help shortcuts", 1_000L);
        assertEquals("!macros", viaAlias.embed().title());
    }

    @Test
    @DisplayName("paging still works, so the two uses do not collide")
    void pagingStillWorks() {
        AssistantBrain.Decision second = runtime().handle("Notch", "!help 2", 1_000L);
        assertTrue(second.hasEmbed());
        assertFalse("!help".equals(second.embed().title()));
        assertTrue(second.embed().footer().contains("Page 2"), second.embed().footer());
    }

    @Test
    @DisplayName("an unknown name falls back to the list rather than an empty answer")
    void unknownNameFallsBack() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!help notacommand", 1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed());
        assertFalse(decision.embed().fields().isEmpty());
    }

    @Test
    @DisplayName("!share lists nothing until a macro is shared")
    void shareIsEmptyByDefault() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!share", 1_000L);

        assertTrue(decision.acts());
        assertFalse(decision.hasEmbed(), "an empty card would render as a blank box");
        assertTrue(decision.text().contains("No macros are shared"), decision.text());
    }

    @Test
    @DisplayName("!help share resolves to the new command")
    void shareIsInTheCatalogue() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!help share", 1_000L);
        assertNotNull(decision.embed());
        assertEquals("!share", decision.embed().title());
    }
}