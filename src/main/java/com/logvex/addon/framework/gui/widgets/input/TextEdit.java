package com.logvex.addon.framework.gui.widgets.input;

/**
 * The text, caret and selection of a single-line field, with no rendering and no game classes.
 *
 * <p>The selection rules are small but easy to get wrong by one character: a caret that sits
 * "before" a character, a selection that must collapse to its start after a delete, a click that
 * lands between two glyphs. Keeping them here rather than inside {@link WTextBox} means they can be
 * tested without a game window, which is the only way to pin them at all.
 *
 * <p>Instances are immutable: every edit returns a new value. That makes the field's history obvious
 * (the old value is still the old value) and removes the class of bug where a failed edit leaves
 * half of the new state behind.
 */
public final class TextEdit {

    private final String text;
    private final int caret;
    private final int anchor;

    private TextEdit(String text, int caret, int anchor) {
        this.text = text == null ? "" : text;
        int limit = this.text.length();
        this.caret = Math.max(0, Math.min(caret, limit));
        this.anchor = Math.max(0, Math.min(anchor, limit));
    }

    /** An edit whose caret and anchor sit at the end of {@code text}. */
    public static TextEdit of(String text) {
        String value = text == null ? "" : text;
        return new TextEdit(value, value.length(), value.length());
    }

    public String text() {
        return text;
    }

    public int caret() {
        return caret;
    }

    public int anchor() {
        return anchor;
    }

    public int start() {
        return Math.min(caret, anchor);
    }

    public int end() {
        return Math.max(caret, anchor);
    }

    public boolean hasSelection() {
        return start() != end();
    }

    public String selected() {
        return text.substring(start(), end());
    }

    /** Selects everything, with the caret left at the end. */
    public TextEdit selectAll() {
        return new TextEdit(text, text.length(), 0);
    }

    /**
     * Moves the caret.
     *
     * @param extend {@code true} keeps the anchor, so a selection grows or shrinks; {@code false}
     *     drops the selection, which is what an unmodified arrow key does
     */
    public TextEdit moveCaret(int position, boolean extend) {
        int at = Math.max(0, Math.min(position, text.length()));
        return new TextEdit(text, at, extend ? anchor : at);
    }

    /** Places the caret without touching the anchor, for a drag that is still in progress. */
    public TextEdit dragTo(int position) {
        return moveCaret(position, true);
    }

    /** Removes the selection, leaving the caret where the removed text began. */
    public TextEdit deleteSelection() {
        if (!hasSelection()) return this;
        int from = start();
        return new TextEdit(text.substring(0, from) + text.substring(end()), from, from);
    }

    /** Inserts {@code value} at the caret, replacing any selection. */
    public TextEdit insert(String value) {
        if (value == null || value.isEmpty()) return this;
        TextEdit base = deleteSelection();
        int at = base.caret;
        String merged = base.text.substring(0, at) + value + base.text.substring(at);
        int caretAt = at + value.length();
        return new TextEdit(merged, caretAt, caretAt);
    }

    /** Backspace: deletes the selection, or the character before the caret. */
    public TextEdit backspace() {
        if (hasSelection()) return deleteSelection();
        if (caret == 0) return this;
        return new TextEdit(text.substring(0, caret - 1) + text.substring(caret), caret - 1, caret - 1);
    }

    /** Forward delete: removes the selection, or the character after the caret. */
    public TextEdit deleteForward() {
        if (hasSelection()) return deleteSelection();
        if (caret >= text.length()) return this;
        return new TextEdit(text.substring(0, caret) + text.substring(caret + 1), caret, caret);
    }

    /**
     * The caret position nearest a horizontal offset from the field's text origin.
     *
     * @param prefixWidths {@code prefixWidths[i]} is the pixel width of the first {@code i} characters
     * @param localX the pointer offset from the first glyph, in the same unit as the widths
     */
    public static int caretAt(double localX, int[] prefixWidths) {
        if (prefixWidths == null || prefixWidths.length == 0) return 0;
        if (localX <= 0) return 0;
        int last = prefixWidths.length - 1;
        if (localX >= prefixWidths[last]) return last;
        // The first boundary past the pointer brackets it; the nearer of the two sides wins, so a
        // click just left of a glyph puts the caret before it and just right puts it after.
        for (int i = 1; i <= last; i++) {
            if (prefixWidths[i] > localX) {
                double before = prefixWidths[i - 1];
                double after = prefixWidths[i];
                return (localX - before) < (after - localX) ? i - 1 : i;
            }
        }
        return last;
    }

    /** Drops line breaks and other control characters a paste can carry in from outside. */
    public static String sanitise(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TextEdit)) return false;
        TextEdit edit = (TextEdit) other;
        return caret == edit.caret && anchor == edit.anchor && text.equals(edit.text);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(text, caret, anchor);
    }

    @Override
    public String toString() {
        return "TextEdit[" + text + ", caret=" + caret + ", anchor=" + anchor + "]";
    }
}
