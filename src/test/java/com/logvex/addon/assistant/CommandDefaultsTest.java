package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.TriggerRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preconfigured commands and the default rule table.
 *
 * <p>A trigger is matched before a command is parsed, so a default rule that spelled a built-in
 * command would silently shadow it: the user would get the canned reply and never reach the real
 * answer. That is the failure this class exists to prevent.
 */
class CommandDefaultsTest {

    /** The commands the assistant ships ready to use, per the feature list. */
    private static final List<String> SHIPPED = List.of(
        "help", "ping", "tps", "fps", "players", "server", "world");

    @Test
    @DisplayName("every shipped command is in the catalogue and answers")
    void shippedCommandsExist() {
        for (String name : SHIPPED) {
            assertTrue(CommandCatalog.find(name).isPresent(), "missing command: !" + name);
            assertEquals(name, CommandCatalog.canonical(name).orElseThrow(),
                "!" + name + " must resolve to itself");
        }
    }

    @Test
    @DisplayName("the documented spellings of the shipped commands all resolve")
    void documentedAliasesResolve() {
        // These are the alternate spellings the README names, so they must keep working.
        assertEquals("ping", CommandCatalog.canonical("latency").orElseThrow());
        assertEquals("ping", CommandCatalog.canonical("ms").orElseThrow());
        assertEquals("tps", CommandCatalog.canonical("tickrate").orElseThrow());
        assertEquals("fps", CommandCatalog.canonical("frames").orElseThrow());
        assertEquals("players", CommandCatalog.canonical("online").orElseThrow());
        assertEquals("world", CommandCatalog.canonical("dimension").orElseThrow());
        assertEquals("uptime", CommandCatalog.canonical("session").orElseThrow());
        assertEquals("speed", CommandCatalog.canonical("velocity").orElseThrow());
        assertEquals("hello", CommandCatalog.canonical("hi").orElseThrow());
        assertEquals("gg", CommandCatalog.canonical("goodgame").orElseThrow());
    }

    @Test
    @DisplayName("no default trigger shadows a built-in command")
    void defaultsDoNotShadowCommands() {
        // The runtime guarantees this structurally now: a prefixed line is only offered to triggers
        // written with the prefix, so a bare "hello" rule cannot answer "!hello". A shipped rule that
        // spells a command *with* the prefix would still be a trap, so the table is checked for that.
        List<String> clashes = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.defaults()) {
            String pattern = rule.pattern().toLowerCase(Locale.ROOT).strip();
            if (pattern.startsWith("!")) clashes.add(pattern);
        }
        assertTrue(clashes.isEmpty(), "these default triggers shadow a command: " + clashes);
    }

    @Test
    @DisplayName("a prefixed command reaches its command even when a bare-word trigger exists")
    void prefixedCommandBeatsBareTrigger() {
        // "hello" is both a shipped trigger and a shipped command. Typing "!hello" must run the
        // command, while typing "hello" in chat must still get the trigger reply.
        com.logvex.addon.assistant.core.AssistantRuntime runtime =
            new com.logvex.addon.assistant.core.AssistantRuntime(
                new com.logvex.addon.assistant.core.AssistantMemory());
        runtime.setTriggers(com.logvex.addon.assistant.core.TriggerSet.of(
            List.of(TriggerRule.of("hello", "canned trigger reply"))));
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        String bare = runtime.handle("p", "hello", 1_000L).text();
        assertEquals("canned trigger reply", bare, "unprefixed chat must still hit the trigger");

        String prefixed = runtime.handle("p", "!hello", 2_000L).text();
        assertFalse("canned trigger reply".equals(prefixed),
            "a prefixed command must not be answered by a bare-word trigger");
    }

    @Test
    @DisplayName("every default trigger carries the one-second cooldown")
    void defaultsUseTheShortCooldown() {
        for (TriggerRule rule : TriggerRule.defaults()) {
            assertEquals(TriggerRule.DEFAULT_COOLDOWN_MS, rule.cooldownMs(),
                "default rule '" + rule.pattern() + "' has the wrong cooldown");
        }
    }

    @Test
    @DisplayName("no command name is also claimed as another command's alias")
    void aliasTableHasNoCollisions() {
        for (String name : SHIPPED) {
            assertTrue(CommandCatalog.canonical(name).isPresent(), name);
        }
        // A canonical name must never be reachable as an alias of a different command.
        for (CommandCatalog.Command command : CommandCatalog.all()) {
            for (String alias : command.aliases()) {
                String resolved = CommandCatalog.canonical(alias).orElseThrow();
                assertEquals(command.name(), resolved,
                    "alias '" + alias + "' resolves to " + resolved + ", not " + command.name());
                assertFalse(CommandCatalog.all().stream()
                        .anyMatch(other -> !other.name().equals(command.name())
                            && other.name().equals(alias)),
                    "alias '" + alias + "' is also a command name");
            }
        }
    }

    @Test
    @DisplayName("the shipped commands are enabled by default")
    void shippedCommandsAreOnByDefault() {
        for (String name : SHIPPED) {
            CommandCatalog.Command command = CommandCatalog.find(name).orElseThrow();
            assertEquals(com.logvex.addon.assistant.core.PermissionLevel.EVERYONE,
                command.defaultLevel(),
                "!" + name + " should be available without a permission entry");
        }
    }

    // ------------------------------------------------------------------ macro and report commands

    @Test
    @DisplayName("the macro and report commands are in the catalogue")
    void macroAndReportCommandsExist() {
        for (String name : List.of("macros", "macrohelp", "mod", "report", "who")) {
            assertTrue(CommandCatalog.find(name).isPresent(), "missing command: !" + name);
            assertEquals(name, CommandCatalog.canonical(name).orElseThrow());
        }
    }

    @Test
    @DisplayName("the documented macro and report aliases resolve to their commands")
    void macroAndReportAliases() {
        assertEquals("macros", CommandCatalog.canonical("shortcuts").orElseThrow());
        assertEquals("macrohelp", CommandCatalog.canonical("macro").orElseThrow());
        assertEquals("mod", CommandCatalog.canonical("mods").orElseThrow());
        assertEquals("report", CommandCatalog.canonical("alert").orElseThrow());
        assertEquals("who", CommandCatalog.canonical("onlineplayers").orElseThrow());
        assertEquals("who", CommandCatalog.canonical("list").orElseThrow());
    }

    @Test
    @DisplayName("reading the macro list does not need a permission entry")
    void macroListIsPublic() {
        assertEquals(com.logvex.addon.assistant.core.PermissionLevel.EVERYONE,
            CommandCatalog.find("macros").orElseThrow().defaultLevel());
        assertEquals(com.logvex.addon.assistant.core.PermissionLevel.EVERYONE,
            CommandCatalog.find("macrohelp").orElseThrow().defaultLevel());
    }

    @Test
    @DisplayName("a report is marked as writing, so it can be rate limited like other state changes")
    void reportWrites() {
        // Reporting posts to an external service; it is not a read-only figure like !ping.
        assertTrue(CommandCatalog.find("report").orElseThrow().writes());
        assertTrue(CommandCatalog.find("mod").orElseThrow().writes());
        assertFalse(CommandCatalog.find("macros").orElseThrow().writes());
    }

    @Test
    @DisplayName("no default trigger shadows the new commands")
    void defaultsDoNotShadowNewCommands() {
        for (TriggerRule rule : TriggerRule.defaults()) {
            String pattern = rule.pattern().toLowerCase(Locale.ROOT).strip();
            assertFalse(CommandCatalog.canonical(pattern)
                    .filter(name -> List.of("macros", "macrohelp", "mod", "report", "who").contains(name))
                    .isPresent(),
                "default trigger '" + pattern + "' would shadow a command");
        }
    }
}
