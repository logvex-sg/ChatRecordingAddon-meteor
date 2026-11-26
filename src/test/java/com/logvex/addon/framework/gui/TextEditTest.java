package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.widgets.input.TextEdit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caret and selection rules behind every text box.
 *
 * <p>These are the behaviours a user notices immediately when they are wrong: a paste that lands one
 * character off, a backspace that eats the wrong glyph, a select-all that does not select, a click
 * that puts the caret past the end. Each is pinned here because the field itself needs a game window
 * and cannot be exercised in a unit test.
 */
class TextEditTest {

    @Test
    @DisplayName("a new edit puts the caret at the end with nothing selected")
    void startsAtTheEnd() {
        TextEdit edit = TextEdit.of("hello");

        assertEquals("hello", edit.text());
        assertEquals(5, edit.caret());
        assertFalse(edit.hasSelection());
        assertEquals("", edit.selected());
    }

    @Test
    @DisplayName("a null or missing value is an empty field, not a crash")
    void nullIsEmpty() {
        assertEquals("", TextEdit.of(null).text());
        assertEquals(0, TextEdit.of(null).caret());
    }

    @Test
    @DisplayName("typing inserts at the caret and leaves the caret after the new text")
    void insertAtCaret() {
        TextEdit edit = TextEdit.of("ac").moveCaret(1, false).insert("b");

        assertEquals("abc", edit.text());
        assertEquals(2, edit.caret());
    }

    @Test
    @DisplayName("pasting replaces the selection rather than doubling it")
    void pasteReplacesSelection() {
        // Select "b" in "abc" and paste "XY": the field must read "aXYc", not "aXYbc".
        TextEdit edit = TextEdit.of("abc").moveCaret(1, false).dragTo(2).insert("XY");

        assertEquals("aXYc", edit.text());
        assertEquals(3, edit.caret());
        assertFalse(edit.hasSelection(), "the caret collapses after a paste");
    }

    @Test
    @DisplayName("select-all selects the whole field with the caret at the end")
    void selectAll() {
        TextEdit edit = TextEdit.of("a rule line").selectAll();

        assertTrue(edit.hasSelection());
        assertEquals("a rule line", edit.selected());
        assertEquals(0, edit.start());
        assertEquals("a rule line".length(), edit.end());
    }

    @Test
    @DisplayName("a selection can be made backwards, and still reads the same text")
    void backwardsSelection() {
        TextEdit edit = TextEdit.of("abcdef").moveCaret(4, false).dragTo(1);

        assertEquals(1, edit.start());
        assertEquals(4, edit.end());
        assertEquals("bcd", edit.selected());
    }

    @Test
    @DisplayName("deleting a selection leaves the caret where the text began")
    void deleteSelectionCollapses() {
        TextEdit edit = TextEdit.of("abcdef").moveCaret(1, false).dragTo(4).deleteSelection();

        assertEquals("aef", edit.text());
        assertEquals(1, edit.caret());
        assertFalse(edit.hasSelection());
    }

    @Test
    @DisplayName("backspace removes the character before the caret, never past the start")
    void backspace() {
        TextEdit edit = TextEdit.of("abc").moveCaret(2, false).backspace();
        assertEquals("ac", edit.text());
        assertEquals(1, edit.caret());

        TextEdit atStart = TextEdit.of("abc").moveCaret(0, false).backspace();
        assertEquals("abc", atStart.text(), "backspace at the start changes nothing");
        assertEquals(0, atStart.caret());
    }

    @Test
    @DisplayName("backspace with a selection removes the selection, not one more character")
    void backspaceWithSelection() {
        TextEdit edit = TextEdit.of("abcdef").moveCaret(1, false).dragTo(4).backspace();

        assertEquals("aef", edit.text(), "only the selected text goes");
        assertEquals(1, edit.caret());
    }

    @Test
    @DisplayName("forward delete removes the character after the caret, never past the end")
    void deleteForward() {
        TextEdit edit = TextEdit.of("abc").moveCaret(1, false).deleteForward();
        assertEquals("ac", edit.text());
        assertEquals(1, edit.caret());

        TextEdit atEnd = TextEdit.of("abc").deleteForward();
        assertEquals("abc", atEnd.text(), "delete at the end changes nothing");
    }

    @Test
    @DisplayName("an unmodified arrow drops the selection; a shifted arrow keeps it")
    void arrowSelection() {
        TextEdit selected = TextEdit.of("abcd").moveCaret(1, false).dragTo(3);
        TextEdit plain = selected.moveCaret(4, false);
        assertFalse(plain.hasSelection(), "an arrow without shift collapses the selection");
        assertEquals(4, plain.caret());

        TextEdit shifted = selected.moveCaret(4, true);
        assertTrue(shifted.hasSelection());
        assertEquals("bcd", shifted.selected());
    }

    @Test
    @DisplayName("the caret cannot leave the field")
    void caretIsClamped() {
        TextEdit edit = TextEdit.of("abc");

        assertEquals(0, edit.moveCaret(-10, false).caret());
        assertEquals(3, edit.moveCaret(99, false).caret());
    }

    @Test
    @DisplayName("an edit never mutates the value it was made from")
    void editsAreImmutable() {
        TextEdit original = TextEdit.of("abc");
        TextEdit edited = original.insert("d");

        assertEquals("abc", original.text(), "the old value is unchanged");
        assertEquals("abcd", edited.text());
        assertNotSame(original, edited);
    }

    @Test
    @DisplayName("a no-op delete returns the same value rather than a copy")
    void noOpReturnsThis() {
        TextEdit edit = TextEdit.of("abc");
        assertSame(edit, edit.deleteSelection(), "nothing selected, nothing to delete");
    }

    @Test
    @DisplayName("a click maps to the nearest character boundary")
    void caretFromPointer() {
        // Four one-character glyphs, ten pixels each: boundaries at 0, 10, 20, 30, 40.
        int[] widths = {0, 10, 20, 30, 40};

        assertEquals(0, TextEdit.caretAt(-5, widths), "left of the text");
        assertEquals(0, TextEdit.caretAt(0, widths));
        assertEquals(1, TextEdit.caretAt(9, widths), "just left of the second glyph");
        assertEquals(2, TextEdit.caretAt(15, widths), "the nearer side wins");
        assertEquals(4, TextEdit.caretAt(40, widths), "right of the text");
        assertEquals(4, TextEdit.caretAt(500, widths), "far right is clamped to the end");
    }

    @Test
    @DisplayName("a paste is cleaned of line breaks and control characters")
    void pasteIsSanitised() {
        assertEquals("a b c", TextEdit.sanitise("a\nb\tc"), "line breaks and tabs become spaces");
        assertEquals("ab", TextEdit.sanitise("a\u0000b"), "other control characters are dropped");
        assertEquals("", TextEdit.sanitise(null));
        assertEquals("oak_log", TextEdit.sanitise("oak_log"), "ordinary text is untouched");
    }

    @Test
    @DisplayName("a multi-line paste into a one-line box stays on one line")
    void multiLinePasteStaysOneLine() {
        TextEdit edit = TextEdit.of("").insert(TextEdit.sanitise("first\r\nsecond"));

        assertEquals("first  second", edit.text());
        assertFalse(edit.text().contains("\n"), "no line break can reach the field");
    }

    @Test
    @DisplayName("value equality, so two identical edits compare equal")
    void equality() {
        assertEquals(TextEdit.of("x").moveCaret(0, false), TextEdit.of("x").moveCaret(0, false));
        assertFalse(TextEdit.of("x").moveCaret(0, false).equals(TextEdit.of("x")));
    }
}
