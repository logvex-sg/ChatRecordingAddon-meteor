package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.MacroSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat command macro table: parsing, argument binding and expansion.
 *
 * <p>These are the behaviours that decide whether a shortcut becomes the right command, a usage
 * line, or nothing at all. The dangerous outcomes are a half-substituted command reaching the server
 * and a shortcut being posted as chat, so most of these tests are about refusing to send.
 */
class ChatMacroTest {

    private static ChatMacro macro(String line) {
        ChatMacro parsed = ChatMacro.decode(line);
        assertNotNull(parsed, "should have parsed: " + line);
        return parsed;
    }

    // ------------------------------------------------------------------ the one-line syntax

    @Test
    @DisplayName("a plain shortcut with no arguments expands to its command")
    void noArguments() {
        ChatMacro spawn = macro("!spawn => /spawn");
        assertTrue(spawn.valid());
        assertEquals("!spawn", spawn.shortcut());
        assertEquals("!spawn", spawn.usageLine());
        assertTrue(spawn.args().isEmpty());

        ChatMacro.Outcome outcome = spawn.run("");
        assertTrue(outcome.ok());
        assertEquals("/spawn", outcome.command());
    }

    @Test
    @DisplayName("a placeholder is replaced by the argument the player typed")
    void singlePlaceholder() {
        ChatMacro tpa = macro("!tpa <player> => /tpa {player}");
        assertEquals(List.of(new ChatMacro.Arg("player", false, false)), tpa.args());

        ChatMacro.Outcome outcome = tpa.run("Steve");
        assertTrue(outcome.ok());
        assertEquals("/tpa Steve", outcome.command());
    }

    @Test
    @DisplayName("the shortcut is matched case-insensitively")
    void caseInsensitiveShortcut() {
        ChatMacro tpa = macro("!tpa <player> => /tpa {player}");
        assertTrue(tpa.triggers().stream().anyMatch(t -> t.equalsIgnoreCase("!TPA")));
    }

    @Test
    @DisplayName("a multi-word argument absorbs the rest of the line")
    void restArgument() {
        ChatMacro msg = macro("!msg <player> <message> => /msg {player} {message}");
        ChatMacro.Outcome outcome = msg.run("Alex hello there friend");
        assertTrue(outcome.ok());
        assertEquals("/msg Alex hello there friend", outcome.command());
    }

    @Test
    @DisplayName("a {message} argument must be last, because it consumes the rest")
    void restArgumentMustBeLast() {
        assertFalse(macro("!x <message> <player> => /x {message} {player}").valid());
        assertTrue(macro("!x <player> <message> => /x {player} {message}").valid());
    }

    @Test
    @DisplayName("an optional argument left out is omitted, not sent as an empty gap")
    void optionalArgument() {
        ChatMacro home = macro("!home <name> [world] => /home {name} {world}");
        ChatMacro.Outcome outcome = home.run("base");
        assertTrue(outcome.ok());
        assertEquals("/home base", outcome.command());

        ChatMacro.Outcome both = home.run("base nether");
        assertTrue(both.ok());
        assertEquals("/home base nether", both.command());
    }

    // ------------------------------------------------------------------ refusing to send

    @Test
    @DisplayName("a missing required argument returns a usage line and no command")
    void missingArgument() {
        ChatMacro.Outcome outcome = macro("!tpa <player> => /tpa {player}").run("");
        assertFalse(outcome.ok());
        assertEquals("", outcome.command());
        assertTrue(outcome.problem().contains("player"));
        assertEquals("!tpa <player>", outcome.usageLine());
    }

    @Test
    @DisplayName("extra words are refused rather than silently dropped")
    void tooManyArguments() {
        ChatMacro.Outcome outcome = macro("!tpa <player> => /tpa {player}").run("Alex extra");
        assertFalse(outcome.ok());
        assertTrue(outcome.problem().toLowerCase().contains("too many"));
    }

    @Test
    @DisplayName("a template that would send plain chat is not a command macro")
    void templateMustBeACommand() {
        ChatMacro chat = macro("!hi => hello everyone");
        assertFalse(chat.valid(), "a template without a leading slash would post as chat");
        assertFalse(chat.run("").ok());
    }

    @Test
    @DisplayName("an unknown placeholder is refused, not left in the command")
    void unknownPlaceholder() {
        ChatMacro broken = macro("!x <player> => /x {nobody}");
        ChatMacro.Outcome outcome = broken.run("Alex");
        assertFalse(outcome.ok());
        assertTrue(outcome.problem().contains("nobody"));
    }

    @Test
    @DisplayName("a placeholder for an argument that was never declared is refused")
    void placeholderWithoutArgument() {
        ChatMacro broken = macro("!x => /x {player}");
        assertFalse(broken.run("Alex").ok());
    }

    @Test
    @DisplayName("a newline in the input cannot split one command into two")
    void newlineCollapsed() {
        // The tokenizer splits on whitespace, so a newline becomes an ordinary word break and the
        // command stays on one line.
        ChatMacro.Outcome outcome = macro("!x <message> => /say {message}").run("one\ntwo");
        assertTrue(outcome.ok());
        assertEquals("/say one two", outcome.command());
        assertFalse(outcome.command().contains("\n"));
    }

    // ------------------------------------------------------------------ round-tripping

    @Test
    @DisplayName("a macro survives encode and decode unchanged")
    void encodeRoundTrip() {
        for (ChatMacro original : ChatMacro.defaults()) {
            ChatMacro decoded = ChatMacro.decode(original.encode());
            assertNotNull(decoded, "re-decoded: " + original.encode());
            assertEquals(original.usage(), decoded.usage());
            assertEquals(original.template(), decoded.template());
            assertEquals(original.description(), decoded.description());
            assertEquals(original.enabled(), decoded.enabled());
            assertEquals(original.triggers(), decoded.triggers());
        }
    }

    @Test
    @DisplayName("a hand-written line with no trailing segments loads as written")
    void plainLineDecodes() {
        ChatMacro macro = macro("!back => /back");
        assertEquals("!back", macro.usage());
        assertEquals("/back", macro.template());
        assertTrue(macro.description().isEmpty());
        assertTrue(macro.enabled());
    }

    @Test
    @DisplayName("the off flag and aliases survive a round trip")
    void flagsRoundTrip() {
        ChatMacro macro = macro("!x <a> => /x {a} ; my description ; off ; aliases=!y,!z");
        assertFalse(macro.enabled());
        assertEquals("my description", macro.description());
        assertEquals(List.of("!y", "!z"), macro.aliases());
        assertEquals(List.of("!x", "!y", "!z"), macro.triggers());
    }

    @Test
    @DisplayName("a comment or blank line is not a macro")
    void nonMacroLines() {
        assertNull(ChatMacro.decode(""));
        assertNull(ChatMacro.decode("   "));
        assertNull(ChatMacro.decode("# a note"));
        assertNull(ChatMacro.decode("!no arrow here"));
        assertNull(ChatMacro.decode("!x =>"));
        assertNull(ChatMacro.decode("=> /x"));
    }

    // ------------------------------------------------------------------ the table

    @Test
    @DisplayName("a message without the prefix is never a macro")
    void prefixRequired() {
        MacroSet set = MacroSet.of(ChatMacro.defaults(), "!");
        assertTrue(set.resolve("spawn").isEmpty());
        assertTrue(set.resolve("hello all").isEmpty());
        assertTrue(set.resolve(null).isEmpty());
    }

    @Test
    @DisplayName("an unknown shortcut falls through to normal chat")
    void unknownShortcut() {
        MacroSet set = MacroSet.of(ChatMacro.defaults(), "!");
        assertTrue(set.resolve("!notamacro").isEmpty());
    }

    @Test
    @DisplayName("a disabled macro is not resolved, so it cannot run")
    void disabledMacro() {
        ChatMacro disabled = new ChatMacro("!spawn", "!spawn", "/spawn", "", false, List.of());
        MacroSet set = MacroSet.of(List.of(disabled), "!");
        assertTrue(set.resolve("!spawn").isEmpty());
        assertTrue(set.enabled().isEmpty());
    }

    @Test
    @DisplayName("a valid macro resolves with the text after the shortcut")
    void resolvesWithInput() {
        MacroSet set = MacroSet.of(ChatMacro.defaults(), "!");
        MacroSet.Call call = set.resolve("!tpa Alex").orElseThrow();
        assertEquals("!tpa", call.macro().shortcut());
        assertEquals("Alex", call.input());
    }

    @Test
    @DisplayName("an alias resolves to the same macro")
    void aliasResolves() {
        ChatMacro withAlias = new ChatMacro("!spawn", "!spawn", "/spawn", "", true,
            List.of("!hub"));
        MacroSet set = MacroSet.of(List.of(withAlias), "!");
        assertEquals("!spawn", set.resolve("!hub").orElseThrow().macro().shortcut());
    }

    @Test
    @DisplayName("two macros on one spelling are reported, not silently ordered")
    void duplicatesReported() {
        ChatMacro a = new ChatMacro("!x", "!x", "/a", "", true, List.of());
        ChatMacro b = new ChatMacro("!X", "!X", "/b", "", true, List.of());
        List<String> duplicates = MacroSet.of(List.of(a, b), "!").duplicates();
        assertEquals(List.of("!x"), duplicates);
    }

    @Test
    @DisplayName("the first macro on a shared spelling is the one that runs")
    void firstWins() {
        ChatMacro a = new ChatMacro("!x", "!x", "/a", "", true, List.of());
        ChatMacro b = new ChatMacro("!x", "!x", "/b", "", true, List.of());
        MacroSet set = MacroSet.of(List.of(a, b), "!");
        assertEquals("/a", set.resolve("!x").orElseThrow().macro().template());
    }

    @Test
    @DisplayName("a macro that would send its own shortcut is caught before sending")
    void recursionDetected() {
        ChatMacro loop = new ChatMacro("!x", "!x", "/x", "", true, List.of());
        MacroSet set = MacroSet.of(List.of(loop), "!");
        assertTrue(set.wouldRecurse(loop, "!x"));
        assertFalse(set.wouldRecurse(loop, "/x"));
    }

    @Test
    @DisplayName("a custom prefix is honoured everywhere")
    void customPrefix() {
        MacroSet set = MacroSet.of(
            List.of(new ChatMacro(".spawn", ".spawn", "/spawn", "", true, List.of())), ".");
        assertTrue(set.resolve("!spawn").isEmpty());
        assertEquals("/spawn", set.resolve(".spawn").orElseThrow().macro().run("").command());
    }

    @Test
    @DisplayName("stored lines that will not parse are skipped, not fatal")
    void badLinesSkipped() {
        MacroSet set = MacroSet.decode(List.of("!good => /good", "rubbish", "", "!also => /also"),
            "!");
        assertEquals(2, set.macros().size());
    }

    @Test
    @DisplayName("every shipped macro is valid and its usage matches its template")
    void shippedMacrosValid() {
        for (ChatMacro macro : ChatMacro.defaults()) {
            assertTrue(macro.valid(), "invalid shipped macro: " + macro.encode());
            assertFalse(macro.template().isBlank());
            assertTrue(macro.template().startsWith("/"), macro.encode());
        }
    }

    @Test
    @DisplayName("every placeholder in a shipped macro names a declared argument")
    void shippedPlaceholdersDeclared() {
        for (ChatMacro macro : ChatMacro.defaults()) {
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (ChatMacro.Arg arg : macro.args()) declared.add(arg.name());
            java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-z]+)}").matcher(macro.template());
            while (matcher.find()) {
                assertTrue(declared.contains(matcher.group(1)),
                    macro.encode() + " uses {" + matcher.group(1) + "} without declaring it");
            }
        }
    }

    @Test
    @DisplayName("no two shipped macros claim the same spelling")
    void shippedMacrosUnique() {
        assertTrue(MacroSet.of(ChatMacro.defaults(), "!").duplicates().isEmpty());
    }

    @Test
    @DisplayName("the shipped defaults cover the shortcuts the feature list names")
    void shippedShortcuts() {
        MacroSet set = MacroSet.of(ChatMacro.defaults(), "!");
        for (String shortcut : List.of("!tpa", "!spawn", "!home", "!msg", "!back")) {
            assertTrue(set.find(shortcut).isPresent(), "missing shipped macro: " + shortcut);
        }
    }
}
