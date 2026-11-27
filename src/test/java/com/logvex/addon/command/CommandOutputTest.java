package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.ReplyFormat.Field;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the chat line cap.
 *
 * <p>These exist because the bug they describe shipped once: the block was rendered in full and
 * then cut with {@code subList}, which left a box ending on a bare {@code │} with no closing rule.
 * The renderer was already correct and already tested — the defect was that the command path never
 * called the cap-safe method. So these tests go through the same entry point the commands use.
 */
class CommandOutputTest {

    private static List<Field> manyFields(int count) {
        List<Field> fields = new ArrayList<>();
        fields.add(Field.section("State"));
        fields.add(new Field("Automation", "idle"));
        fields.add(Field.section("Queue"));
        for (int i = 1; i <= count; i++) {
            fields.add(new Field("#" + i, "GATHERING minecraft:oak_log (" + i + "/32) QUEUED"));
        }
        return fields;
    }

    @Test
    void aCappedBoxStillEndsOnItsClosingRule() {
        var rendered = CommandOutput.renderForChat("Automation tasks", manyFields(14), "",
            ReplyFormat.Style.BOX, ReplyFormat.DEFAULT_WIDTH, 12);

        assertTrue(rendered.lines().size() <= 12,
            "the cap is honoured: " + rendered.lines().size());
        String last = rendered.lines().get(rendered.lines().size() - 1);
        assertTrue(last.startsWith("\u2514"),
            "a box cut to fit must still end on its closing rule, got: '" + last + "'");
        assertTrue(last.endsWith("\u2518"), "the closing rule ends in a corner: " + last);
    }

    @Test
    void aCappedBoxNeverEndsOnABareFrame() {
        for (int fields = 1; fields <= 30; fields++) {
            for (int cap = 3; cap <= 16; cap++) {
                var rendered = CommandOutput.renderForChat("Tasks", manyFields(fields), "",
                    ReplyFormat.Style.BOX, ReplyFormat.DEFAULT_WIDTH, cap);
                String last = rendered.lines().get(rendered.lines().size() - 1);
                assertTrue(last.startsWith("\u2514"),
                    "fields=" + fields + " cap=" + cap + " ended on: '" + last + "'");
            }
        }
    }

    @Test
    void aBlockThatFitsExactlyIsNotReportedAsCut() {
        var rendered = CommandOutput.renderForChat("Small", List.of(
            new Field("Automation", "idle")), "", ReplyFormat.Style.BOX,
            ReplyFormat.DEFAULT_WIDTH, 12);

        assertEquals(0, rendered.omitted(),
            "nothing was dropped, so nothing should be claimed as dropped");
    }

    @Test
    void aCutBlockReportsHowManyLinesWereDropped() {
        var rendered = CommandOutput.renderForChat("Automation tasks", manyFields(14), "",
            ReplyFormat.Style.BOX, ReplyFormat.DEFAULT_WIDTH, 12);

        assertTrue(rendered.omitted() > 0, "a cut block must say how much is missing");
    }

    @Test
    void everyStyleCanBePreviewed() {
        // The preview exists so a user can see a style before choosing it. A style with no preview
        // would send the reader to the settings to guess, so each one must render something.
        for (ReplyFormat.Style style : ReplyFormat.Style.values()) {
            List<String> lines = ReplyFormat.sample(style, ReplyFormat.DEFAULT_WIDTH);
            assertTrue(!lines.isEmpty(), style + " must render a sample");
            for (String line : lines) {
                assertTrue(!line.isBlank(), style + " must not emit a blank line");
            }
        }
    }

    @Test
    void theBoxSampleIsBorderedAndTheOthersAreNot() {
        // Each style is visibly different from the others; otherwise the preview cannot teach the
        // difference it exists to show.
        List<String> box = ReplyFormat.sample(ReplyFormat.Style.BOX, ReplyFormat.DEFAULT_WIDTH);
        assertTrue(box.get(0).startsWith("\u250c"), "box opens with a top-left corner");
        assertTrue(box.get(box.size() - 1).startsWith("\u2514"), "box closes with a bottom-left corner");

        List<String> plain = ReplyFormat.sample(ReplyFormat.Style.PLAIN, ReplyFormat.DEFAULT_WIDTH);
        for (String line : plain) {
            assertTrue(!line.contains("\u2502") && !line.contains("\u250c"),
                "plain must carry no frame: " + line);
        }
        assertTrue(plain.size() < box.size(), "plain must be shorter than a full box");
    }

    @Test
    void thePreviewHonoursTheChosenWidth() {
        List<String> wide = ReplyFormat.sample(ReplyFormat.Style.BOX, 60);
        List<String> narrow = ReplyFormat.sample(ReplyFormat.Style.BOX, 34);
        assertTrue(narrow.get(0).length() < wide.get(0).length(),
            "a narrow preview must be narrower than a wide one");
    }

    @Test
    void noPreviewIsCutOffByItsOwnWidth() {
        // A preview that ellipsises itself looks like a rendering bug rather than an example, so
        // every style must fit its sample inside the width at the default the user starts with.
        for (ReplyFormat.Style style : ReplyFormat.Style.values()) {
            for (String line : ReplyFormat.sample(style, ReplyFormat.DEFAULT_WIDTH)) {
                assertTrue(!line.contains("\u2026"),
                    style + " must not truncate its own sample: " + line);
            }
        }
    }
}
