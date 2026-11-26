package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card format: a status answer and a command-shaped trigger both render as a boxed block.
 *
 * <p>These tests pin the parts a reader notices. The figures have to be aligned rather than run
 * together in one sentence, the block has to close properly at the width chat actually allows, and
 * the footer has to explain the card instead of repeating the command name back at the reader.
 */
class EmbedFormatTest {

    private static final ServerStats STATS = new ServerStats(
        true, "mc.example.net", 19.6f, 42, 60, 200, 7_200_000L, "minecraft:overworld", "overworld",
        "plains", 6_000L, 17.5f, 20f, 15, 120.4, 64.0, -35.9, "north", 5.43, 240, 512L, 4096L,
        List.of("Alice", "Bob"));

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    private static AssistantBrain.Decision ask(String command) {
        return runtime().handle(SenderIdentity.local("Me", null), command, 5_000L, STATS, null);
    }

    private static String description(String command) {
        return CommandCatalog.find(command).map(CommandCatalog.Command::description).orElseThrow();
    }

    @Test
    @DisplayName("a status answer is a boxed card with one figure per row")
    void statusAnswerIsACard() {
        AssistantBrain.Decision decision = ask("!status");

        assertNotNull(decision);
        assertTrue(decision.hasEmbed(), "a status answer should be a card");
        ReplyFormat.Embed embed = decision.embed();
        assertTrue(embed.fields().size() >= 3, "expected several figures, got " + embed.fields());

        List<String> lines = ReplyFormat.render(embed, 48);
        assertTrue(lines.get(0).startsWith("\u250c"),
            "the card must open with a corner: " + lines.get(0));
        assertTrue(lines.get(lines.size() - 1).startsWith("\u2514"),
            "the card must close with a corner: " + lines.get(lines.size() - 1));
        for (String line : lines) {
            assertTrue(line.length() <= 48,
                "line over the width budget (" + line.length() + "): " + line);
        }
    }

    @Test
    @DisplayName("the card footer explains the command, it does not repeat its name")
    void footerExplainsTheCard() {
        ReplyFormat.Embed embed = ask("!status").embed();

        assertEquals(description("status"), embed.footer());
        assertFalse(embed.footer().contains("!status"),
            "the footer repeated the title instead of explaining it: " + embed.footer());
        assertTrue(embed.footer().length() > 10, "the footer should be a real sentence");
    }

    @Test
    @DisplayName("a rendered card names the command once, in the title")
    void titleNamesTheCommand() {
        ReplyFormat.Embed embed = ask("!memory").embed();

        assertEquals("!memory", embed.title());
        String rendered = String.join("\n", ReplyFormat.render(embed, 48));
        assertEquals(1, rendered.split("!memory", -1).length - 1,
            "the command name appears more than once:\n" + rendered);
    }

    @Test
    @DisplayName("a command-shaped trigger is a card too, with its description in the footer")
    void triggerCardCarriesItsDescription() {
        AssistantBrain.Decision decision = ask("!tps");

        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed(), "a figures trigger should be a card");
        assertEquals("!tps", decision.embed().title());
        assertEquals(description("tps"), decision.embed().footer());
    }

    @Test
    @DisplayName("an ordinary phrase trigger stays one line, with no card")
    void phraseTriggerStaysOneLine() {
        AssistantBrain.Decision decision = ask("hello");

        assertTrue(decision.acts());
        assertFalse(decision.hasEmbed(), "a greeting is not a table of figures");
        assertFalse(decision.text().isBlank());
    }

    @Test
    @DisplayName("a card with a long value still closes on its own rule")
    void longValueDoesNotBreakTheBox() {
        // A narrow width forces the wrap path, which is where a box can lose its closing rule.
        ReplyFormat.Embed embed = ask("!serverinfo").embed();
        List<String> lines = ReplyFormat.render(embed, 40);

        for (String line : lines) {
            assertTrue(line.length() <= 40, "line over the width budget: " + line);
        }
        assertTrue(lines.get(lines.size() - 1).startsWith("\u2514"),
            "the closing rule was lost when a value wrapped");
    }
}
