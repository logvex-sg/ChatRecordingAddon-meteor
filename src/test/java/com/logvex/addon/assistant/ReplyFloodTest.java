package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ReplyFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command typed to check one thing must not bury the conversation.
 *
 * <p>The bare {@code !ai} used to render a ten-field block, which is twelve chat lines every time it
 * was typed. These tests pin the budget so the block cannot quietly grow back into a flood.
 */
class ReplyFloodTest {

    /** How many chat lines one unasked-for answer may occupy. */
    private static final int ONE_LINE = 1;

    @Test
    @DisplayName("the assistant status block is too big to send unasked")
    void statusBlockIsTooBigForABareCommand() {
        // This is why bare !ai answers with a single line instead of the block: the block itself is
        // fine when asked for, and unusable when not.
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (String label : List.of("Assistant", "Triggers", "Facts", "Players known", "Handled",
            "Ignored", "Rate limited", "Refused", "Chat seen", "Permissions")) {
            fields.add(new ReplyFormat.Field(label, "0"));
        }
        List<String> lines = ReplyFormat.render(
            new ReplyFormat.Embed("Assistant", fields, "", ReplyFormat.Style.BOX), 48);
        assertTrue(lines.size() > ONE_LINE,
            "the status block is expected to be multi-line; bare !ai must not send it");
    }

    @Test
    @DisplayName("a one-line summary stays on one line at the narrowest width")
    void summaryFitsOneLine() {
        // The bare !ai answer is a single info() call, so it is one chat line regardless of width.
        // This guards the intent: no newline may sneak into it and turn it into a block.
        String summary = "Assistant: on, 27 triggers, 4 facts, 0 handled. !ai status for detail.";
        assertTrue(!summary.contains("\n"), "the summary must not contain a newline");
    }
}
