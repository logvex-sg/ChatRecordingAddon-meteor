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
        List<String> clashes = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.defaults()) {
            String pattern = rule.pattern().toLowerCase(Locale.ROOT).strip();
            // A bare command word as a trigger would answer before the command is ever parsed.
            if (CommandCatalog.canonical(pattern).isPresent()) clashes.add(pattern);
        }
        assertTrue(clashes.isEmpty(), "these default triggers shadow a command: " + clashes);
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
}
