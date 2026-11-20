package com.logvex.addon.framework;

import com.logvex.addon.common.CommandLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rule that decides whether a chat line is an addon command.
 *
 * <p>Both failure modes are silent in game: too loose and ordinary chat is swallowed instead of
 * sent, too tight and the command does nothing. Each case below pins one of those edges.
 */
class CommandLineTest {
    private static final List<String> OWN = List.of("log", "ai", "bot", "la", "b");

    @Test
    @DisplayName("a prefixed command resolves to its body, arguments intact")
    void resolvesCommand() {
        assertEquals("log gui", CommandLine.body("!log gui", "!", OWN));
        assertEquals("ai status", CommandLine.body("!ai status", "!", OWN));
        assertEquals("log", CommandLine.body("!log", "!", OWN));
    }

    @Test
    @DisplayName("an alias is a command, and the name is matched in any case")
    void resolvesAliasAndCase() {
        assertEquals("la status", CommandLine.body("!la status", "!", OWN));
        assertEquals("log gui", CommandLine.body("!LOG gui", "!", OWN));
        assertEquals("bot start", CommandLine.body("!Bot start", "!", OWN));
    }

    @Test
    @DisplayName("a command typed in capitals still dispatches, because names are folded")
    void commandNameIsFolded() {
        // Brigadier registers literals in lower case, so passing `Log` through would match no
        // command after the line had already been cancelled — the command would vanish silently.
        assertEquals("log", CommandLine.body("!LOG", "!", OWN));
        assertEquals("log", CommandLine.body("!LoG", "!", OWN));
    }

    @Test
    @DisplayName("arguments keep their original case")
    void argumentsKeepCase() {
        assertEquals("log webhook set MyToken", CommandLine.body("!log webhook set MyToken", "!", OWN));
    }

    @Test
    @DisplayName("ordinary chat is never a command, even when it starts with the prefix")
    void ordinaryChatPassesThrough() {
        assertNull(CommandLine.body("!is this thing on", "!", OWN));
        assertNull(CommandLine.body("!gg everyone", "!", OWN));
        assertNull(CommandLine.body("hello there", "!", OWN));
        assertNull(CommandLine.body("/spawn", "!", OWN));
        assertNull(CommandLine.body("", "!", OWN));
    }

    @Test
    @DisplayName("the prefix alone, or with only spaces, is not a command")
    void barePrefixIsNotACommand() {
        assertNull(CommandLine.body("!", "!", OWN));
        assertNull(CommandLine.body("!   ", "!", OWN));
    }

    @Test
    @DisplayName("the prefix must be at the very start, not merely present")
    void prefixMustLead() {
        assertNull(CommandLine.body("say !log gui", "!", OWN));
        assertNull(CommandLine.body(" !log gui", "!", OWN));
    }

    @Test
    @DisplayName("a command name is matched whole, never as a prefix of a longer word")
    void nameIsWholeWord() {
        assertNull(CommandLine.body("!logger gui", "!", OWN));
        assertNull(CommandLine.body("!bots", "!", OWN));
    }

    @Test
    @DisplayName("the configured prefix is honoured, not a hardcoded one")
    void usesConfiguredPrefix() {
        assertEquals("log gui", CommandLine.body(".log gui", ".", OWN));
        assertNull(CommandLine.body("!log gui", ".", OWN));
        assertEquals("log gui", CommandLine.body("$log gui", "$", OWN));
    }

    @Test
    @DisplayName("a multi-character prefix works")
    void multiCharacterPrefix() {
        assertEquals("log gui", CommandLine.body(";;log gui", ";;", OWN));
        assertNull(CommandLine.body(";log gui", ";;", OWN));
    }

    @Test
    @DisplayName("no prefix, or no known commands, routes nothing")
    void degenerateInputsRouteNothing() {
        assertNull(CommandLine.body("!log gui", "", OWN));
        assertNull(CommandLine.body("!log gui", null, OWN));
        assertNull(CommandLine.body("!log gui", "!", null));
        assertNull(CommandLine.body("!log gui", "!", List.of()));
        assertNull(CommandLine.body(null, "!", OWN));
    }

    @Test
    @DisplayName("extra whitespace after the prefix does not defeat the match")
    void toleratesWhitespaceAfterPrefix() {
        assertEquals("log gui", CommandLine.body("!  log gui", "!", OWN));
        assertEquals("log gui", CommandLine.body("!\tlog gui", "!", OWN));
    }
}
