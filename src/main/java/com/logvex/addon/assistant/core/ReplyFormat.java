package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a reply as a tidy block instead of a run-on sentence.
 *
 * <p>The idea is borrowed from chat embeds: a title, a short list of labelled fields, and a footer,
 * laid out so the values line up and the eye can find the number it wants. Minecraft chat cannot
 * carry a newline in one message, so a block is returned as a list of lines and the caller sends
 * them in order. That constraint is also why the width is capped: a line that wraps in the chat box
 * destroys the alignment the block exists to provide.
 *
 * <p>Three styles are offered, and the plainest is always available as a fallback, because a user
 * who dislikes box drawing should not have to give up the figures.
 */
public final class ReplyFormat {

    /** How a block is laid out. */
    public enum Style {
        /** A bordered block, one field per line. The default for multi-field answers. */
        BOX,
        /** One line: {@code Title | label value | label value}. */
        COMPACT,
        /** No decoration at all: {@code label value, label value}. */
        PLAIN,
        /** One {@code label - value} line per field, for lists such as the command help. */
        LIST;

        public static Style parse(String value, Style fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "box", "boxed", "block", "embed" -> BOX;
                case "compact", "line", "inline", "single" -> COMPACT;
                case "plain", "none", "off", "text" -> PLAIN;
                case "list", "lines" -> LIST;
                default -> fallback;
            };
        }
    }

    /** One labelled figure. */
    public record Field(String label, String value) {
        public Field {
            if (label == null) label = "";
            if (value == null) value = "";
        }

        /**
         * A figure with a proportional bar, so a share is readable without doing the arithmetic.
         *
         * <p>{@code "Heap  Heap 42% ##########----------"} is a lot to read; a bar shows at a glance
         * that 42% is not "nearly full". The number stays in the value because a bar alone cannot
         * be read precisely.
         */
        public static Field meter(String label, String value, double fraction) {
            return new Field(label, value + " " + bar(fraction, METER_CELLS));
        }

        /** A rule across the block with a name on it, to separate groups of figures. */
        public static Field section(String name) {
            return new Field("", SECTION + (name == null ? "" : name));
        }
    }

    /** How many cells a meter draws. Short enough to sit inside a 48-column box with a label. */
    private static final int METER_CELLS = 12;

    /** Marks a field as a section rule rather than a figure. */
    private static final String SECTION = "\u0000section:";

    /**
     * A proportional bar, {@code fraction} clamped to 0..1.
     *
     * <p>Uses a light and a dark block rather than colour: the chat box is drawn in one colour and a
     * client may have colour codes disabled, so the bar has to read in plain text.
     */
    public static String bar(double fraction, int cells) {
        int n = Math.max(1, cells);
        double f = Double.isNaN(fraction) ? 0 : Math.min(1, Math.max(0, fraction));
        int filled = (int) Math.round(f * n);
        return "\u2588".repeat(filled) + "\u2591".repeat(n - filled);
    }

    /** A block ready to render. */
    public record Embed(String title, List<Field> fields, String footer, Style style) {
        public Embed {
            if (title == null) title = "";
            if (fields == null) fields = List.of();
            if (footer == null) footer = "";
            if (style == null) style = Style.BOX;
            fields = List.copyOf(fields);
        }

        public static Embed of(String title, List<Field> fields) {
            return new Embed(title, fields, "", Style.BOX);
        }
    }

    /** The widest a rendered line may be before values are shortened. */
    public static final int DEFAULT_WIDTH = 48;

    private static final String TOP_LEFT = "\u250c";
    private static final String TOP_RIGHT = "\u2510";
    private static final String BOTTOM_LEFT = "\u2514";
    private static final String BOTTOM_RIGHT = "\u2518";
    private static final String BAR = "\u2502";
    private static final String HORIZONTAL = "\u2500";
    private static final String ELLIPSIS = "\u2026";
    /** Prefix for a wrapped list row, so the continuation reads as part of the entry above. */
    private static final String CONTINUATION = "  ";

    private ReplyFormat() {
    }

    /** Renders {@code embed} with its own style and {@link #DEFAULT_WIDTH}. */
    public static List<String> render(Embed embed) {
        return render(embed, DEFAULT_WIDTH);
    }

    /**
     * Renders {@code embed}.
     *
     * <p>Text is wrapped, never truncated, so a field is always complete; a block therefore stays
     * honest at any width. An embed with no title, fields or footer renders nothing at all.
     *
     * @param width the maximum visible characters per line
     */
    public static List<String> render(Embed embed, int width) {
        if (embed == null) return List.of();
        int limit = width <= 0 ? DEFAULT_WIDTH : width;
        return switch (embed.style()) {
            case BOX -> renderBox(embed, limit);
            case COMPACT -> List.of(renderCompact(embed, limit));
            case PLAIN -> List.of(renderPlain(embed, limit));
            case LIST -> renderList(embed, limit);
        };
    }

    /** Renders the embed on one line whatever its style, for callers that can only send one. */
    public static String oneLine(Embed embed, int width) {
        if (embed == null) return "";
        return renderCompact(embed, width <= 0 ? DEFAULT_WIDTH : width);
    }

    /**
     * Builds a card from a rendered status template such as {@code "TPS: 19.9 | MSPT: 12"}.
     *
     * <p>Status answers are user-editable templates, so the card cannot be built from a fixed list
     * of fields — it has to follow whatever the operator wrote. The template's own separators carry
     * the structure: {@code |} splits fields and the first {@code :} in a field splits its label
     * from its value. A segment with no colon keeps its text as an unlabelled value, so a template
     * like {@code "XYZ: 1, 2, 3 | overworld | plains"} still renders every part instead of
     * silently dropping the ones without labels.
     *
     * @param title the card heading, usually the command name
     * @param text  the already-rendered template output
     * @return the card, or null when the text has no content to lay out
     */
    public static Embed fromTemplate(String title, String text) {
        if (text == null || text.isBlank()) return null;
        List<Field> fields = new ArrayList<>();
        for (String segment : text.split("\\s*\\|\\s*")) {
            String part = segment.strip();
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            // A colon in the first position is not a label separator ("10:30"), and a value that
            // is only punctuation would render as an empty row.
            if (colon > 0) {
                String label = part.substring(0, colon).strip();
                String value = part.substring(colon + 1).strip();
                if (!value.isEmpty()) {
                    fields.add(new Field(label, value));
                    continue;
                }
            }
            fields.add(new Field("", part));
        }
        if (fields.isEmpty()) return null;
        return new Embed(title == null ? "" : title, fields, "", Style.BOX);
    }

    /**
     * Renders at most {@code maxLines} lines, keeping the block whole.
     *
     * <p>Cutting a rendered list with {@code subList} is the obvious approach and it is wrong: a box
     * that is cut before its closing rule ends on a {@code │} row, and a list that is cut stops
     * mid-sentence. Either reads as a message that failed to send. So the cap is applied to the
     * <em>fields</em> instead and the block is rendered again, and the rows that did not fit are
     * counted in the footer rather than dropped silently.
     *
     * <p>COMPACT and PLAIN collapse to one line, so there is no field to drop there; a single line
     * that still overflows means the caller's width is too small, and truncating is the only option.
     *
     * @param maxLines the most lines the caller can send, at least one
     */
    public static List<String> renderCapped(Embed embed, int width, int maxLines) {
        if (embed == null) return List.of();
        if (maxLines <= 0) return List.of();
        int limit = width <= 0 ? DEFAULT_WIDTH : width;
        List<String> full = render(embed, limit);
        if (full.size() <= maxLines) return full;
        if (embed.style() == Style.COMPACT || embed.style() == Style.PLAIN) {
            return List.copyOf(full.subList(0, maxLines));
        }

        // Two frame lines for a box, a title line for a list; the loop corrects any guess that was
        // wrong, because a wrapped value can cost more rows than the field count suggests.
        List<Field> fields = embed.fields();
        int keep = Math.min(fields.size(), Math.max(1, maxLines - 2));
        while (keep > 0) {
            List<Field> subset = fields.subList(0, keep);
            String footer = embed.footer();
            if (keep < fields.size()) {
                String omitted = "+" + (fields.size() - keep) + " more";
                footer = footer == null || footer.isBlank() ? omitted : footer + " · " + omitted;
            }
            List<String> lines = render(new Embed(embed.title(), subset, footer, embed.style()), limit);
            if (lines.size() <= maxLines) return lines;
            keep--;
        }
        return List.copyOf(full.subList(0, maxLines));
    }

    private static List<String> renderBox(Embed embed, int width) {
        if (embed.fields().isEmpty() && embed.title().isBlank() && embed.footer().isBlank()) {
            return List.of();
        }
        int labelWidth = 0;
        boolean anyLabel = false;
        for (Field field : embed.fields()) {
            labelWidth = Math.max(labelWidth, displayWidth(field.label()));
            if (!field.label().isBlank()) anyLabel = true;
        }
        // With no labels at all there is no column to align to, so a value starts at the frame
        // instead of being pushed right by an empty label column.
        if (!anyLabel) labelWidth = 0;
        // A long label must not squeeze the value to nothing, so it is capped and wrapped instead.
        // The cap is a share of the box, not just "width minus a little": one long label would
        // otherwise take almost the whole row and leave the value a one-word-wide column.
        int labelCap = Math.max(8, (width - 5) * 2 / 5);
        if (labelWidth > 0) {
            labelWidth = Math.max(1, Math.min(labelWidth, Math.min(labelCap, width - 6)));
        }

        List<String> lines = new ArrayList<>(embed.fields().size() + 2);
        if (!embed.title().isBlank()) {
            lines.add(border(TOP_LEFT, TOP_RIGHT, embed.title(), width));
        }
        // "│ " + label + " " + value + " │" costs five columns of framing; without a label
        // column one of those spaces is not used, so the value gets it.
        int room = Math.max(1, width - (labelWidth == 0 ? 4 : 5) - labelWidth);
        for (Field field : embed.fields()) {
            if (field.value().startsWith(SECTION)) {
                // A section is a rule across the block, so it ignores the label column entirely
                // rather than starting inside it and looking like an indented figure.
                lines.add(BAR + " " + rule(field.value().substring(SECTION.length()), width - 4)
                    + " " + BAR);
                continue;
            }
            List<String> label = labelWidth == 0 ? List.of() : wrap(field.label(), labelWidth);
            List<String> value = wrap(field.value(), room);
            int rows = Math.max(label.size(), value.size());
            for (int i = 0; i < rows; i++) {
                String left = pad(i < label.size() ? label.get(i) : "", labelWidth);
                String right = pad(i < value.size() ? value.get(i) : "", room);
                lines.add(labelWidth == 0
                    ? BAR + " " + right + " " + BAR
                    : BAR + " " + left + " " + right + " " + BAR);
            }
        }
        // A box is always closed. An open box reads as a truncated message, not a block.
        lines.add(border(BOTTOM_LEFT, BOTTOM_RIGHT, embed.footer(), width));
        return List.copyOf(lines);
    }

    /** A horizontal rule with a corner at each end and an optional label after the first. */
    private static String border(String left, String right, String label, int width) {
        // A label wider than the rule would push the right corner off the line, so it is shortened.
        // Two corners and one leading rule take three columns; the label and fill take the rest.
        String text = label.isBlank() ? "" : " " + label + " ";
        int room = width - 3;
        if (displayWidth(text) > room) {
            text = " " + shorten(label, Math.max(1, room - 2)) + " ";
        }
        int fill = Math.max(0, room - displayWidth(text));
        return left + HORIZONTAL + text + HORIZONTAL.repeat(fill) + right;
    }

    /** A filled rule with an optional name in the middle of the row. */
    private static String rule(String name, int width) {
        int room = Math.max(1, width);
        String text = name == null || name.isBlank() ? "" : " " + name + " ";
        int textWidth = displayWidth(text);
        if (textWidth >= room) {
            return HORIZONTAL.repeat(room);
        }
        int fill = room - textWidth;
        int left = fill / 2;
        return HORIZONTAL.repeat(left) + text + HORIZONTAL.repeat(fill - left);
    }

    private static List<String> renderList(Embed embed, int width) {
        List<String> lines = new ArrayList<>(embed.fields().size() + 2);
        if (!embed.title().isBlank()) lines.addAll(wrap(embed.title(), width));
        for (Field field : embed.fields()) {
            if (field.value().startsWith(SECTION)) {
                lines.addAll(wrap(rule(field.value().substring(SECTION.length()), width), width));
                continue;
            }
            String row = field.label().isBlank()
                ? field.value()
                : field.label() + " - " + field.value();
            // Rows wrap rather than truncate: a help row whose permission level was cut off is
            // worse than one that continues on the next line. Continuations are indented so they
            // read as part of the entry above instead of as an unlabelled new one.
            lines.addAll(wrapIndented(row, width, CONTINUATION));
        }
        if (!embed.footer().isBlank()) lines.addAll(wrap(embed.footer(), width));
        return List.copyOf(lines);
    }

    /**
     * Wraps {@code text} and prefixes every continuation line with {@code indent}.
     *
     * <p>The indent is applied <em>before</em> measuring, not after. Wrapping first and prefixing
     * afterwards pushes each continuation two columns past the limit, and re-wrapping that pushes it
     * past again — which is how a long row ended up with one continuation line flush at column zero,
     * reading as a separate entry rather than the rest of the row above.
     */
    static List<String> wrapIndented(String text, int width, String indent) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return List.copyOf(out);
        }
        String lead = indent == null ? "" : indent;
        int indentWidth = displayWidth(lead);
        // An indent wider than the line leaves no room to continue into; fall back to no indent
        // rather than looping forever on a row that can never be made to fit.
        if (indentWidth >= width) {
            return wrap(text, width);
        }
        String rest = text;
        boolean first = true;
        while (true) {
            int room = first ? width : width - indentWidth;
            String prefix = first ? "" : lead;
            if (displayWidth(rest) <= room) {
                out.add(prefix + rest);
                break;
            }
            int cut = breakAt(rest, room);
            out.add(prefix + rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
            first = false;
            if (rest.isEmpty()) break;
        }
        return List.copyOf(out);
    }

    /**
     * Breaks {@code text} into lines of at most {@code width} characters, preferring spaces.
     *
     * <p>Wrapping rather than shortening is what keeps a block honest: the field is still complete,
     * it simply continues on the next line under the same label.
     */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return List.copyOf(out);
        }
        int limit = Math.max(1, width);
        String rest = text;
        while (displayWidth(rest) > limit) {
            int cut = breakAt(rest, limit);
            out.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        out.add(rest);
        return List.copyOf(out);
    }

    /**
     * The index to cut {@code text} at so the head occupies at most {@code limit} columns.
     *
     * <p>Prefers the last space inside the budget, so words are not split, and falls back to the
     * budget itself when a single word is wider than the line. Cutting is by code point so a
     * surrogate pair is never split, and by column so a wide glyph is never counted as one.
     */
    private static int breakAt(String text, int limit) {
        int used = 0;
        int lastSpace = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int w = codePointWidth(cp);
            if (used + w > limit) {
                // A space before the budget is the nicer cut; otherwise cut exactly here.
                return lastSpace > 0 ? lastSpace : i;
            }
            if (cp == ' ') lastSpace = i;
            used += w;
            i += Character.charCount(cp);
        }
        return i;
    }

    private static String renderCompact(Embed embed, int width) {
        StringBuilder sb = new StringBuilder();
        if (!embed.title().isBlank()) sb.append(embed.title()).append(" | ");
        for (int i = 0; i < embed.fields().size(); i++) {
            if (i > 0) sb.append(" | ");
            Field field = embed.fields().get(i);
            if (!field.label().isBlank()) sb.append(field.label()).append(' ');
            sb.append(field.value());
        }
        if (!embed.footer().isBlank()) sb.append(" | ").append(embed.footer());
        return shorten(sb.toString(), width);
    }

    private static String renderPlain(Embed embed, int width) {
        StringBuilder sb = new StringBuilder();
        if (!embed.title().isBlank()) sb.append(embed.title()).append(": ");
        for (int i = 0; i < embed.fields().size(); i++) {
            if (i > 0) sb.append(", ");
            Field field = embed.fields().get(i);
            if (!field.label().isBlank()) sb.append(field.label()).append(' ');
            sb.append(field.value());
        }
        if (!embed.footer().isBlank()) sb.append(". ").append(embed.footer());
        return shorten(sb.toString(), width);
    }

    private static String pad(String text, int width) {
        int visible = displayWidth(text);
        if (visible >= width) return text;
        return text + " ".repeat(width - visible);
    }

    /**
     * Columns a string occupies, counting a wide character as two.
     *
     * <p>Chat is drawn with a fixed-width font where a CJK ideograph or an emoji is twice as wide as
     * an ASCII letter, so measuring {@code length()} pads such a row two columns short and the right
     * frame drifts left. Combining marks take no column at all.
     */
    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            width += codePointWidth(cp);
        }
        return width;
    }

    /** Columns one code point occupies: 0 for a combining mark, 2 for a wide character, else 1. */
    private static int codePointWidth(int cp) {
        if (cp == 0) return 0;
        if (Character.getType(cp) == Character.NON_SPACING_MARK) return 0;
        if (isWide(cp)) return 2;
        return 1;
    }

    /**
     * True for the code point ranges a terminal or Minecraft's font draws double width.
     *
     * <p>Deliberately a range test rather than a table: the exact east-asian-width database is large
     * and changes between Unicode versions, while these ranges cover what actually appears in a
     * player name or a chat line.
     */
    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
            || (cp >= 0x2E80 && cp <= 0xA4CF)      // CJK radicals .. Yi
            || (cp >= 0xAC00 && cp <= 0xD7A3)      // Hangul syllables
            || (cp >= 0xF900 && cp <= 0xFAFF)      // CJK compatibility ideographs
            || (cp >= 0xFE30 && cp <= 0xFE6F)      // CJK compatibility forms
            || (cp >= 0xFF00 && cp <= 0xFF60)      // Fullwidth forms
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x1F300 && cp <= 0x1F64F)    // emoji
            || (cp >= 0x1F900 && cp <= 0x1F9FF)
            || (cp >= 0x20000 && cp <= 0x3FFFD);   // CJK extension B+
    }

    private static String shorten(String text, int width) {
        if (text == null) return "";
        if (width <= 0) return "";
        if (displayWidth(text) <= width) return text;
        if (width == 1) return ELLIPSIS;
        // Cut by code point and by column, so a surrogate pair is never split and a wide character
        // is never counted as one column.
        int budget = width - 1;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = codePointWidth(cp);
            if (used + w > budget) break;
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return sb + ELLIPSIS;
    }
}
