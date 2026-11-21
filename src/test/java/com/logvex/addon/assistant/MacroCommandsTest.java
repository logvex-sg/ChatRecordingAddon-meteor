package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.MacroSet;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat commands added with the macro rework, driven through the real decision path.
 *
 * <p>These call {@link AssistantRuntime#handle} rather than the private handlers, so what is asserted
 * is what a player typing the command would actually get, including the permission gate and the reply
 * mode.
 */
class MacroCommandsTest {

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        runtime.brain().setMacros(MacroSet.of(ChatMacro.defaults(), "!"));
        return runtime;
    }

    private static ServerStats online(int players, List<String> names) {
        return new ServerStats(true, "mc.example.net", 20f, 42, players, 60, 60_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L,
            20f, 20f, 20, 0, 64, 0, "north", 0, 60, 512L, 4096L, names);
    }

    // ------------------------------------------------------------------ !macros

    @Test
    @DisplayName("!macros answers with a block, one row per shortcut")
    void macrosListsShortcuts() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macros", 1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed(), "the list should be a block, not one long sentence");
        assertEquals("Chat command macros", decision.embed().title());
        assertTrue(decision.embed().fields().size() >= 5);
    }

    @Test
    @DisplayName("!macros names the shortcut for each entry, so the reply is usable as a reference")
    void macrosRowsCarryUsage() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macros", 1_000L);
        String rendered = String.join(" ", decision.lines(60, null));
        assertTrue(rendered.contains("!tpa"), rendered);
        assertTrue(rendered.contains("!spawn"), rendered);
    }

    @Test
    @DisplayName("!macros with nothing enabled says so instead of an empty card")
    void macrosEmpty() {
        AssistantRuntime runtime = runtime();
        runtime.brain().setMacros(MacroSet.EMPTY);
        AssistantBrain.Decision decision = runtime.handle("Notch", "!macros", 1_000L);
        assertTrue(decision.acts());
        assertFalse(decision.hasEmbed(), "an empty card would render as a blank box");
        assertTrue(decision.text().contains("No chat command macros"), decision.text());
    }

    @Test
    @DisplayName("a disabled macro is absent from !macros")
    void macrosHidesDisabled() {
        AssistantRuntime runtime = runtime();
        runtime.brain().setMacros(MacroSet.of(List.of(
            new ChatMacro("!spawn", "!spawn", "/spawn", "Go spawn", true, List.of()),
            new ChatMacro("!secret", "!secret", "/secret", "Hidden", false, List.of())), "!"));
        String rendered = String.join(" ", runtime.handle("Notch", "!macros", 1_000L).lines(60, null));
        assertTrue(rendered.contains("!spawn"), rendered);
        assertFalse(rendered.contains("!secret"), rendered);
    }

    // ------------------------------------------------------------------ !macrohelp

    @Test
    @DisplayName("!macrohelp names one macro's syntax and the command it sends")
    void macroHelpOne() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macrohelp tpa", 1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed());
        String rendered = String.join(" ", decision.lines(60, null));
        assertTrue(rendered.contains("!tpa <player>"), rendered);
        assertTrue(rendered.contains("/tpa {player}"), rendered);
    }

    @Test
    @DisplayName("!macrohelp accepts the shortcut with or without the prefix")
    void macroHelpPrefixOptional() {
        AssistantRuntime runtime = runtime();
        String bare = String.join(" ", runtime.handle("Notch", "!macrohelp tpa", 1_000L).lines(60, null));
        String prefixed = String.join(" ",
            runtime.handle("Notch", "!macrohelp !tpa", 1_001L).lines(60, null));
        assertTrue(bare.contains("!tpa <player>"), bare);
        assertTrue(prefixed.contains("!tpa <player>"), prefixed);
    }

    @Test
    @DisplayName("!macrohelp with no name explains how to use it")
    void macroHelpUsage() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macrohelp", 1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.text().contains("!macrohelp <name>"), decision.text());
    }

    @Test
    @DisplayName("!macrohelp on an unknown name says so rather than guessing")
    void macroHelpUnknown() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macrohelp nonsense", 1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.text().contains("No macro named"), decision.text());
    }

    // ------------------------------------------------------------------ !who

    @Test
    @DisplayName("!who lists the visible players as a block")
    void whoLists() {
        AssistantBrain.Decision decision = runtime().handle(SenderIdentity.unverified("Notch"), "!who", 1_000L,
            online(3, List.of("Alex", "Steve", "Herobrine")), StatusCommands.Settings.DEFAULTS, -1);
        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed());
        String rendered = String.join(" ", decision.lines(60, null));
        assertTrue(rendered.contains("Alex"), rendered);
        assertTrue(rendered.contains("Steve"), rendered);
    }

    @Test
    @DisplayName("!who reports the visible count against the server's own count")
    void whoCountsVisible() {
        AssistantBrain.Decision decision = runtime().handle(SenderIdentity.unverified("Notch"), "!who", 1_000L,
            online(10, List.of("Alex", "Steve")), StatusCommands.Settings.DEFAULTS, -1);
        String rendered = String.join(" ", decision.lines(60, null));
        assertTrue(rendered.contains("2 of 10"), rendered);
    }

    @Test
    @DisplayName("!who is answered as a single sentence when the client is not connected")
    void whoOffline() {
        AssistantBrain.Decision decision = runtime().handle(SenderIdentity.unverified("Notch"), "!who", 1_000L,
            ServerStats.OFFLINE, StatusCommands.Settings.DEFAULTS, -1);
        assertTrue(decision.acts());
        assertFalse(decision.hasEmbed(), "there is no roster to lay out when disconnected");
        assertEquals("Not connected.", decision.text());
    }

    // ------------------------------------------------------------------ !report / !mod

    @Test
    @DisplayName("!report carries the reason and asks for a report card")
    void reportCarriesReason() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!report Alex is griefing spawn",
            1_000L);
        assertTrue(decision.acts());
        assertTrue(decision.hasReport(), "the module needs the report to post it");
        String report = decision.report();
        assertTrue(report.startsWith("report|"), report);
        assertTrue(report.contains("Notch"), report);
        assertTrue(report.endsWith("Alex is griefing spawn"), report);
    }

    @Test
    @DisplayName("!mod is marked as a mod call, not a report")
    void modKind() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!mod someone is flying", 1_000L);
        assertTrue(decision.hasReport());
        assertTrue(decision.report().startsWith("mod|"), decision.report());
    }

    @Test
    @DisplayName("!report with no reason explains the syntax and posts nothing")
    void reportNeedsReason() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!report", 1_000L);
        assertTrue(decision.acts());
        assertFalse(decision.hasReport(), "an empty report must never be posted");
        assertTrue(decision.text().contains("!report <what you need help with>"), decision.text());
    }

    @Test
    @DisplayName("!mod with only spaces posts nothing either")
    void modNeedsReason() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!mod    ", 1_000L);
        assertFalse(decision.hasReport());
    }

    @Test
    @DisplayName("the confirmation does not claim delivery, which only the module can know")
    void reportConfirmationIsNeutral() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!report spam", 1_000L);
        assertTrue(decision.acts());
        String text = decision.text().toLowerCase();
        assertTrue(text.contains("report"), decision.text());
        // "Sent" would be a promise the brain cannot keep: no webhook may be configured.
        assertFalse(text.contains("sent"), decision.text());
    }

    @Test
    @DisplayName("a report is rate limited per player, so the webhook cannot be flooded")
    void reportsAreRateLimited() {
        AssistantRuntime runtime = runtime();
        long now = 1_000L;
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (runtime.handle("Notch", "!report spam " + i, now + i).hasReport()) allowed++;
        }
        assertTrue(allowed >= 1, "the first report must go through");
        assertTrue(allowed < 10, "ten reports in one second must not all go through");
    }

    @Test
    @DisplayName("one player's report limit does not block another player")
    void reportLimitIsPerPlayer() {
        AssistantRuntime runtime = runtime();
        for (int i = 0; i < 5; i++) runtime.handle("Spammer", "!report spam " + i, 1_000L + i);
        assertTrue(runtime.handle("Notch", "!report real problem", 1_010L).hasReport(),
            "a different player must still be able to report");
    }

    @Test
    @DisplayName("the reason may contain the separator without corrupting the report")
    void reportReasonWithSeparator() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!report a|b|c", 1_000L);
        assertTrue(decision.hasReport());
        // The module splits on the first two separators only, so the reason keeps its pipes.
        assertTrue(decision.report().endsWith("a|b|c"), decision.report());
    }

    @Test
    @DisplayName("a macro can never be reached by an incoming message")
    void macrosNotReachableFromIncoming() {
        // `!spawn` typed by someone else is chat, not a command, and certainly not a local macro.
        AssistantBrain.Decision decision = runtime().handle("Alex", "!spawn", 1_000L);
        assertNull(decision.report(), "an incoming message must never trigger a macro send");
        assertFalse(decision.command() && decision.text().contains("/spawn"),
            "a macro must not be executed from someone else's chat");
    }

    // ------------------------------------------------------------------ dispatch sanity

    @Test
    @DisplayName("every new command answers instead of falling through to the AI")
    void newCommandsDispatch() {
        AssistantRuntime runtime = runtime();
        for (String command : List.of("!macros", "!macrohelp", "!who", "!report x", "!mod x")) {
            AssistantBrain.Decision decision = runtime.handle(SenderIdentity.unverified("Notch"), command, 1_000L,
                online(1, List.of("Notch")), StatusCommands.Settings.DEFAULTS, -1);
            assertNotNull(decision, command);
            assertTrue(decision.acts(), "no answer for " + command);
        }
    }

    @Test
    @DisplayName("the documented aliases reach the same handlers")
    void aliasesDispatch() {
        AssistantRuntime runtime = runtime();
        assertTrue(runtime.handle("Notch", "!shortcuts", 1_000L).hasEmbed());
        assertTrue(runtime.handle("Notch", "!macro tpa", 1_001L).hasEmbed());
        assertTrue(runtime.handle("Notch", "!alert help me", 1_002L).hasReport());
        assertTrue(runtime.handle("Notch", "!mods help me", 1_003L).hasReport());
        assertTrue(runtime.handle(SenderIdentity.unverified("Notch"), "!onlineplayers", 1_004L,
            online(1, List.of("Notch")), StatusCommands.Settings.DEFAULTS, -1).acts());
    }

    @Test
    @DisplayName("no shipped macro is dead because a command claims its shortcut")
    void noDefaultMacroIsShadowed() {
        // A command is matched before a macro, so a macro whose shortcut is also a command or alias
        // can never run. The shipped !rules macro was dead exactly this way: "rules" was an alias of
        // !triggers. Checking the two tables against each other keeps the next alias from doing it.
        List<String> shortcuts = new ArrayList<>();
        for (ChatMacro macro : ChatMacro.defaults()) shortcuts.add(macro.shortcut());
        List<String> dead = CommandCatalog.shadowedMacros(shortcuts);
        assertTrue(dead.isEmpty(), "these shipped macros can never run: " + dead);
    }

    @Test
    @DisplayName("a default macro really reaches the server command it names")
    void defaultMacrosRun() {
        MacroSet set = MacroSet.of(ChatMacro.defaults(), "!");
        for (ChatMacro macro : ChatMacro.defaults()) {
            // One argument per placeholder the template declares, not a fixed sample: a macro with
            // no placeholders rejects any input as surplus, and feeding it "Steve x" would fail for
            // a reason that has nothing to do with the macro being reachable.
            int arity = macro.template().split("\\{[a-z]+}", -1).length - 1;
            StringBuilder input = new StringBuilder(macro.shortcut());
            for (int i = 0; i < arity; i++) input.append(" arg").append(i);
            java.util.Optional<MacroSet.Call> found = set.resolve(input.toString());
            assertTrue(found.isPresent(), "no macro matched " + input);
            ChatMacro.Outcome outcome = found.get().macro().run(found.get().input());
            assertTrue(outcome.ok(), macro.shortcut() + " failed: " + outcome.problem());
            assertTrue(outcome.command().startsWith("/"),
                macro.shortcut() + " produced " + outcome.command());
        }
    }
}
