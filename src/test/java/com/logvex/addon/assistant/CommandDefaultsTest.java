package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.TriggerAudience;
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
    @DisplayName("a default trigger never shadows a restricted command")
    void defaultsDoNotShadowRestrictedCommands() {
        // A prefixed default ("!tps") is matched before the command parser, so it does take the
        // place of that command. That is only acceptable when the command it replaces is already
        // open to everyone: shadowing a LOCAL_ONLY or administrator command with a public reply
        // would either hide a command from its owner or expose one it must not.
        //
        // A prefixed pattern that is not a command at all (for example "!inventory") shadows
        // nothing and is always allowed.
        List<String> clashes = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.defaults()) {
            String pattern = rule.pattern().toLowerCase(Locale.ROOT).strip();
            if (!pattern.startsWith("!")) continue;
            String verb = pattern.substring(1).split("\\s+", 2)[0];
            if (verb.isEmpty()) continue;

            String canonical = CommandCatalog.canonical(verb).orElse(null);
            if (canonical == null) continue;
            PermissionLevel level = CommandCatalog.defaultLevelFor(canonical);
            if (level != PermissionLevel.EVERYONE) {
                clashes.add(pattern + " would hide the " + level + " command !" + canonical);
            }
        }
        assertTrue(clashes.isEmpty(), "these default triggers shadow a restricted command: "
            + clashes);
    }

    @Test
    @DisplayName("every public default trigger resolves its placeholders")
    void publicDefaultsExpand() {
        // A public rule is written to be read by everyone in chat, so an unexpanded placeholder is
        // visible to the whole server rather than just the author. Each shipped rule is expanded
        // against a full context and must come out with no brace left behind.
        com.logvex.addon.assistant.core.Placeholders placeholders =
            com.logvex.addon.assistant.core.Placeholders.standard();
        com.logvex.addon.stats.ServerStats stats = com.logvex.addon.stats.ServerStats.OFFLINE;
        com.logvex.addon.assistant.core.ResponseTemplate.Context context =
            new com.logvex.addon.assistant.core.ResponseTemplate.Context(
                "p", "", "", new com.logvex.addon.assistant.core.AssistantMemory(),
                1_000L, stats, "assistant", "!");

        List<String> broken = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.defaults()) {
            String pattern = rule.pattern().toLowerCase(Locale.ROOT).strip();
            if (!pattern.startsWith("!")) continue;
            String expanded = placeholders.expand(rule.reply(), context);
            if (expanded.contains("{") || expanded.contains("}")) {
                broken.add(pattern + " -> " + expanded);
            }
        }
        assertTrue(broken.isEmpty(), "these public defaults leave a placeholder unexpanded: "
            + broken);
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
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS
            .withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);

        String bare = runtime.handle("p", "hello", 1_000L).text();
        assertEquals("canned trigger reply", bare, "unprefixed chat must still hit the trigger");

        String prefixed = runtime.handle("p", "!hello", 2_000L).text();
        assertFalse("canned trigger reply".equals(prefixed),
            "a prefixed command must not be answered by a bare-word trigger");
    }

    @Test
    @DisplayName("every default trigger carries the shipped three-second cooldown")
    void defaultsUseTheShippedCooldown() {
        // The shipped table is long and every entry answers out of the box, so it paces itself
        // harder than a hand-written rule does. A rule the user adds still gets the one-second
        // default; only the preconfigured entries carry the longer gap.
        for (TriggerRule rule : TriggerRule.defaults()) {
            assertEquals(TriggerRule.TRIGGER_COOLDOWN_MS, rule.cooldownMs(),
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

    @Test
    @DisplayName("a shipped rule never answers in place of a command that knows more")
    void rulesDoNotShadowRicherCommands() {
        // A prefixed rule is matched before the command parser, so "!who" as a rule replaces the
        // !who command entirely. That is acceptable for a one-figure verb, where the rule and the
        // command carry the same information, but not for a verb whose command answers with more:
        // !who lists the roster and !whoami reports the memory dossier, and a canned line hid both.
        // This test names the verbs whose command is the better answer.
        List<String> richer = List.of("who", "whoami", "history", "facts", "memories", "fact",
            "triggers", "activity", "macros", "help", "cmds", "about", "summary");
        List<String> shadowed = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String pattern = rule.pattern().strip();
            if (!pattern.startsWith("!")) continue;
            // Strip the prefix: without this the lookup is for "!who", which is no canonical name,
            // so the check silently passed for every rule.
            String verb = pattern.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String canonical = CommandCatalog.canonical(verb).orElse(null);
            if (canonical != null && richer.contains(canonical)) {
                shadowed.add(pattern + " replaces !" + canonical);
            }
        }
        assertTrue(shadowed.isEmpty(), "these rules hide a richer command: " + shadowed);
    }
}
