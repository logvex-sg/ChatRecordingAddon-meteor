package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ModCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !modcall} files a report client side, so the things that matter are that the text is
 * cleaned before it reaches the reports channel and that nothing is ever sent to the server.
 */
class ModCallTest {

    private static final String ME = "Alex";

    private static ModCall.Report report(String reason) {
        return ModCall.build(ME, reason, "!");
    }

    @Test
    @DisplayName("a report carries the reporter, the reason and its kind")
    void buildsAReport() {
        ModCall.Report built = report("griefer at spawn");
        assertTrue(built.ok());
        assertEquals(ME, built.reporter());
        assertEquals("griefer at spawn", built.reason());
        assertEquals(ModCall.KIND_MOD, built.kind());
    }

    @Test
    @DisplayName("the report kind is selectable")
    void kindIsSelectable() {
        assertEquals(ModCall.KIND_REPORT,
            ModCall.build(ME, "x", "!", ModCall.KIND_REPORT).kind());
    }

    @Test
    @DisplayName("building a report never produces a server command")
    void neverProducesServerCommands() {
        // The whole point of this feature is that it is client side: no /report (not vanilla), no
        // /msg to a moderator who may be offline. Nothing in the built report is a command.
        ModCall.Report built = report("help me\n/op Steve");
        for (String value : new String[]{built.reason(), built.reporter(), built.kind()}) {
            assertFalse(value.startsWith("/"), "a report field must not be a command: " + value);
            assertFalse(value.contains("\n"), "a report field must be one line: " + value);
        }
    }

    @Test
    @DisplayName("a newline cannot become a second line")
    void newlineIsFlattened() {
        ModCall.Report built = report("help me\n/op Steve");
        assertFalse(built.reason().contains("\n"), "a reason must never span lines");
        assertEquals("help me /op Steve", built.reason());
    }

    @Test
    @DisplayName("carriage returns and other control characters are flattened")
    void controlCharactersAreFlattened() {
        assertEquals("a b c", ModCall.sanitise("a\r\nb\tc"));
        assertEquals("a b", ModCall.sanitise("a\u0000b"));
    }

    @Test
    @DisplayName("a section sign cannot inject chat colour codes")
    void sectionSignIsStripped() {
        assertEquals("RED text", ModCall.sanitise("\u00a7cRED\u00a7r text"));
        assertEquals("red", report("\u00a7cre\u00a7rd").reason());
    }

    @Test
    @DisplayName("a reason of only whitespace is refused")
    void whitespaceOnlyReasonRefused() {
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(ME, "\n\n\t", "!").problem());
    }

    @Test
    @DisplayName("a reason of only control characters is refused")
    void controlOnlyReasonRefused() {
        assertEquals(ModCall.Problem.EMPTY_REASON,
            ModCall.build(ME, "\u0000\u0001", "!").problem());
    }

    @Test
    @DisplayName("an empty reason is refused")
    void reasonIsRequired() {
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(ME, "", "!").problem());
        assertEquals(ModCall.Problem.NO_REASON, ModCall.build(ME, null, "!").problem());
    }

    @Test
    @DisplayName("an over-long reason is truncated, not carried whole")
    void longReasonIsTruncated() {
        assertTrue(report("x".repeat(1_000)).reason().length() <= ModCall.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("a failed report carries no content")
    void failedReportIsEmpty() {
        ModCall.Report failed = ModCall.build(ME, "", "!");
        assertFalse(failed.ok());
        assertTrue(failed.reason().isEmpty());
        assertFalse(ModCall.Problem.NO_REASON.message("!").contains("{prefix}"),
            "the usage hint must have its prefix substituted");
    }

    @Test
    @DisplayName("the reporter name is trimmed")
    void reporterIsTrimmed() {
        assertEquals("Alex", ModCall.build("  Alex  ", "x", "!").reporter());
    }

    @Test
    @DisplayName("the usage line names the command and promises a report, not a message")
    void usageLine() {
        assertTrue(ModCall.usage("!").startsWith("!modcall"));
        assertTrue(ModCall.usage("!").contains("report"));
        assertFalse(ModCall.usage("!").contains("privately message"));
    }

    @Test
    @DisplayName("a description names the kind and the reporter")
    void describe() {
        assertTrue(ModCall.describe(report("x")).startsWith("mod report from Alex"));
        assertEquals("", ModCall.describe(ModCall.build(ME, "", "!")));
    }
}
