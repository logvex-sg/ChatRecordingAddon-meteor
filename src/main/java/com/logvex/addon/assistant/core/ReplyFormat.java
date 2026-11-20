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

    private static List<String> renderBox(Embed embed, int width) {
        if (embed.fields().isEmpty() && embed.title().isBlank() && embed.footer().isBlank()) {
            return List.of();
        }
        int labelWidth = 0;
        boolean anyLabel = false;
        for (Field field : embed.fields()) {
            labelWidth = Math.max(labelWidth, field.label().length());
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
        if (text.length() > room) {
            text = " " + shorten(label, Math.max(1, room - 2)) + " ";
        }
        int fill = Math.max(0, room - text.length());
        return left + HORIZONTAL + text + HORIZONTAL.repeat(fill) + right;
    }

    private static List<String> renderList(Embed embed, int width) {
        List<String> lines = new ArrayList<>(embed.fields().size() + 2);
        if (!embed.title().isBlank()) lines.addAll(wrap(embed.title(), width));
        for (Field field : embed.fields()) {
            String row = field.label().isBlank()
                ? field.value()
                : field.label() + " - " + field.value();
            // Rows wrap rather than truncate: a help row whose permission level was cut off is
            // worse than one that continues on the next line. Continuations are indented so they
            // read as part of the entry above instead of as an unlabelled new one — without the
            // indent a wrapped row looked like two separate commands.
            List<String> wrapped = wrap(row, width);
            lines.add(wrapped.get(0));
            for (int i = 1; i < wrapped.size(); i++) {
                lines.addAll(wrap(CONTINUATION + wrapped.get(i), width));
            }
        }
        if (!embed.footer().isBlank()) lines.addAll(wrap(embed.footer(), width));
        return List.copyOf(lines);
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
        while (rest.length() > limit) {
            int cut = rest.lastIndexOf(' ', limit);
            if (cut <= 0) cut = limit;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
            if (rest.startsWith(" ")) rest = rest.substring(1);
        }
        out.add(rest);
        return List.copyOf(out);
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
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }

    private static String shorten(String text, int width) {
        if (text == null) return "";
        if (width <= 0) return "";
        if (text.length() <= width) return text;
        if (width == 1) return ELLIPSIS;
        return text.substring(0, width - 1) + ELLIPSIS;
    }
}
