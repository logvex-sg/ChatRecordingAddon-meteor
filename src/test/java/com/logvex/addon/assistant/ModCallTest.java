package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ModCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !modcall} turns chat text into server commands, so the two things that matter are that it
 * produces the messages the user expects and that untrusted text cannot become a second command.
 */
class ModCallTest {

    private static final String MOD = "Steve";
    private static final String ME = "Alex";

    private static ModCall.Call call(String reason) {
        return ModCall.build(MOD, reason, "!", ModCall.DEFAULT_MODERATOR_FORMAT,
            ModCall.DEFAULT_REPORTER_FORMAT, ME);
    }

    @Test
    @DisplayName("the moderator is messaged, then the reporter gets the receipt")
    void producesBothMessages() {
        ModCall.Call built = call("griefer at spawn");
        assertTrue(built.ok());
        assertEquals(List.of(
            "msg Steve Report has been submitted: griefer at spawn",
            "msg Alex !report griefer at spawn"
        ), built.commands());
    }

    @Test
    @DisplayName("the moderator message comes first")
    void moderatorIsFirst() {
        assertEquals("msg Steve Report has been submitted: x", call("x").command());
    }

    @Test
    @DisplayName("a newline cannot become a second command")
    void newlineIsNotASecondCommand() {
        ModCall.Call built = call("help me\n/op Steve");
        assertEquals(2, built.commands().size(), "still exactly the two intended messages");
        for (String command : built.commands()) {
            assertFalse(command.contains("\n"), "a command must never span lines: " + command);
        }
        // The newline became a space, so "/op Steve" stays inside the moderator's message instead
        // of being sent as its own command.
        assertEquals("msg Steve Report has been submitted: help me /op Steve", built.command());
        assertEquals(1, built.commands().stream().filter(c -> c.startsWith("msg Steve ")).count(),
            "only one message may go to the moderator");
    }

    @Test
    @DisplayName("carriage returns and other control characters are flattened")
    void controlCharactersAreFlattened() {
        assertEquals("a b c", ModCall.sanitise("a\r\nb\tc"));
        assertEquals("a b", ModCall.sanitise("a\u0000b"));
    }

    @Test
    @DisplayName("a reason of only whitespace is refused")
    void whitespaceOnlyReasonRefused() {
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(MOD, "\n\n\t", "!").problem());
    }

    @Test
    @DisplayName("a reason of only control characters is refused")
    void controlOnlyReasonRefused() {
        // A NUL survives strip(), so this reaches the sanitiser and is emptied there.
        assertEquals(ModCall.Problem.EMPTY_REASON,
            ModCall.build(MOD, "\u0000\u0001", "!").problem());
    }

    @Test
    @DisplayName("an over-long reason is truncated, not sent whole")
    void longReasonIsTruncated() {
        ModCall.Call built = call("x".repeat(1_000));
        assertTrue(built.reason().length() <= ModCall.MAX_REASON_LENGTH);
        assertTrue(built.command().length() < 1_000);
    }

    @Test
    @DisplayName("a blank or invalid target is refused")
    void targetIsValidated() {
        assertEquals(ModCall.Problem.NO_TARGET, ModCall.build("", "x", "!").problem());
        assertEquals(ModCall.Problem.NO_TARGET, ModCall.build(null, "x", "!").problem());
        assertEquals(ModCall.Problem.BAD_TARGET, ModCall.build("Steve Smith", "x", "!").problem());
        assertEquals(ModCall.Problem.BAD_TARGET, ModCall.build("St eve", "x", "!").problem());
        assertEquals(ModCall.Problem.BAD_TARGET, ModCall.build("../../etc", "x", "!").problem());
    }

    @Test
    @DisplayName("an empty reason is refused")
    void reasonIsRequired() {
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(MOD, "", "!").problem());
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(MOD, null, "!").problem());
    }

    @Test
    @DisplayName("a blank receipt format sends only the moderator message")
    void blankReceiptSkipsSecondMessage() {
        ModCall.Call built = ModCall.build(MOD, "x", "!", ModCall.DEFAULT_MODERATOR_FORMAT, "", ME);
        assertEquals(1, built.commands().size());
        assertEquals("msg Steve Report has been submitted: x", built.command());
    }

    @Test
    @DisplayName("the receipt is not sent when the reporter is not a legal name")
    void noReceiptWithoutValidReporter() {
        ModCall.Call built = ModCall.build(MOD, "x", "!",
            ModCall.DEFAULT_MODERATOR_FORMAT, ModCall.DEFAULT_REPORTER_FORMAT, "");
        assertEquals(1, built.commands().size());
    }

    @Test
    @DisplayName("the format tokens expand")
    void tokensExpand() {
        String line = ModCall.render("{reporter} called {moderator}: {reason} ({prefix}help)",
            "grief", MOD, ME, "!");
        assertEquals("Alex called Steve: grief (!help)", line);
    }

    @Test
    @DisplayName("a token inside the reason is not expanded a second time")
    void reasonTokensAreNotReExpanded() {
        // Chained replace() would turn the reason into "Alex is bad", letting a player put words in
        // someone else's mouth.
        assertEquals("{reporter} is bad",
            ModCall.render("{reason}", "{reporter} is bad", MOD, ME, "!"));
        assertEquals("Report: {moderator} said hi",
            ModCall.render("Report: {reason}", "{moderator} said hi", MOD, ME, "!"));
    }

    @Test
    @DisplayName("an unknown token in a format is left alone")
    void unknownTokenIsLiteral() {
        assertEquals("{nope} x", ModCall.render("{nope} {reason}", "x", MOD, ME, "!"));
    }

    @Test
    @DisplayName("an unclosed brace in a format does not swallow the rest")
    void unclosedBraceIsLiteral() {
        assertEquals("{reason x", ModCall.render("{reason x", "ignored", MOD, ME, "!"));
    }

    @Test
    @DisplayName("a section sign cannot inject chat colour codes")
    void sectionSignIsStripped() {
        assertEquals("RED text", ModCall.sanitise("\u00a7cRED\u00a7r text"));
        assertEquals("msg Steve Report has been submitted: red",
            call("\u00a7cre\u00a7rd").command());
    }

    @Test
    @DisplayName("an injection in the reason cannot break out of the format")
    void injectionStaysInsideTheMessage() {
        ModCall.Call built = call("x\nmsg Everyone I am admin");
        assertEquals(2, built.commands().size(), "exactly the two intended messages");
        assertTrue(built.command().startsWith("msg Steve Report has been submitted: "));
    }

    @Test
    @DisplayName("a call that cannot be built carries no commands")
    void failedCallHasNoCommands() {
        ModCall.Call failed = ModCall.build("", "", "!");
        assertFalse(failed.ok());
        assertTrue(failed.commands().isEmpty());
        assertFalse(ModCall.Problem.NO_TARGET.message("!").contains("{prefix}"),
            "the usage hint must have its prefix substituted");
    }

    @Test
    @DisplayName("the usage line names the command")
    void usageLine() {
        assertTrue(ModCall.usage("!").startsWith("!modcall"));
    }

    @Test
    @DisplayName("a target with surrounding spaces is accepted")
    void targetIsTrimmed() {
        assertEquals("Steve", ModCall.build("  Steve  ", "x", "!").target());
    }
}
