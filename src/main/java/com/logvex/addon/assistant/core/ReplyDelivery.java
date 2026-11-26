package com.logvex.addon.assistant.core;

import java.util.List;

/**
 * Decides how many chat messages a reply costs, before anything is sent.
 *
 * <p>A block such as {@code !cmds} renders to a dozen rows, and sending one message per row is a
 * flood: the pacer spread the burst out but still put a dozen {@code /msg} commands on the wire, so
 * a server with a flood filter kicked for a reply the player had only asked for once.
 *
 * <p>The count can always be one, because the asker is usually the player in front of the screen.
 * Their card is printed locally with the rows joined by a newline, which {@code ChatHud} breaks on,
 * so the table keeps its shape and the server sees nothing at all. Only a reply that must reach
 * another player still needs one message per row, since the server relays each one separately.
 *
 * <p>This is a pure function so the rule can be tested without a game client: the module renders
 * the lines and calls this, and the tests assert the plan.
 */
public final class ReplyDelivery {

    /** What the caller should do with {@link Plan#lines()}. */
    public enum Mode {
        /** Print the joined lines as one client-side message. Nothing is sent to the server. */
        LOCAL_ONE_MESSAGE,
        /** Send each line as its own message, paced so the burst stays under a flood filter. */
        SERVER_PACED_LINES,
        /** Send one line to the server. */
        SERVER_ONE_LINE,
        /** Nothing to deliver. */
        NONE
    }

    /** A delivery decision: the mode, and the lines it applies to. */
    public record Plan(Mode mode, List<String> lines) {
        public Plan {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** The lines as one message, for {@link Mode#LOCAL_ONE_MESSAGE}. */
        public String joined() {
            return String.join("\n", lines);
        }

        public boolean isEmpty() {
            return mode == Mode.NONE || lines.isEmpty();
        }
    }

    /**
     * Longest body a single server message may carry.
     *
     * <p>The protocol caps a chat message at 256 characters, and a private reply spends part of that
     * on {@code "/msg <player> "}. 200 leaves room for the longest verb and a full 16-character name
     * without the server rejecting the line, and it is short enough that the count of messages stays
     * small for the widest card this client renders.
     */
    public static final int MAX_MESSAGE_CHARS = 200;

    /** What separates two rows that had to share one message. */
    private static final String ROW_SEPARATOR = " | ";

    private ReplyDelivery() {
    }

    /**
     * Plans the delivery of a reply.
     *
     * @param delivery    how the reply was meant to be delivered
     * @param lines       the rendered rows, already capped
     * @param oneLine     the same reply collapsed to one line, used when a block cannot be sent
     * @param localSpeaker true when the asker is this client's own player
     */
    public static Plan plan(AssistantBrain.Delivery delivery, List<String> lines,
                            String oneLine, boolean localSpeaker) {
        if (delivery == null) return new Plan(Mode.NONE, List.of());
        List<String> rows = lines == null ? List.of() : lines.stream()
            .filter(line -> line != null && !line.isBlank())
            .toList();
        if (rows.isEmpty()) return new Plan(Mode.NONE, List.of());

        return switch (delivery) {
            // A private reply is addressed to one player. When that player is the one who asked on
            // this client, the reply is already on their screen: sending it to the server would be
            // a round trip back to the same place, at the cost of one command per row.
            case PRIVATE -> localSpeaker
                ? new Plan(Mode.LOCAL_ONE_MESSAGE, rows)
                // A remote asker gets a bounded number of messages, never one per row. One message
                // per row made the operator's own account send the whole block, so a stranger
                // typing "!cmds1" put six /msg on the wire from this client and twelve for
                // "!cmds" - and a flood filter kicks the account that sent them, which is the
                // operator, not the asker. Pacing spread that burst out but never reduced the
                // count. The reply is repacked instead, so its content survives and the number of
                // messages is a small constant rather than a function of the card's height.
                : new Plan(Mode.SERVER_PACED_LINES, pack(rows, oneLine));
            // Public chat carries a single line per message, so a block cannot survive the trip.
            // It goes as one collapsed line rather than as a dozen fragments.
            case CHAT -> new Plan(Mode.SERVER_ONE_LINE,
                List.of(rows.size() == 1 ? rows.get(0) : collapse(oneLine, rows)));
            // These are handled by the caller: a notification is not a chat message, and a silent
            // reply is not delivered at all.
            case NOTIFY, SILENT, NONE -> new Plan(Mode.NONE, List.of());
        };
    }

    private static String collapse(String oneLine, List<String> rows) {
        if (oneLine != null && !oneLine.isBlank()) return oneLine;
        return String.join(" | ", rows);
    }

    /**
     * Packs a reply's fields into as few messages as the length cap allows.
     *
     * <p>The compact form of a card is already the shape a chat line can carry - a title, then
     * {@code label value} fields separated by {@code |} - so it is what gets split. Packing the
     * <em>padded box rows</em> instead was a mistake worth recording: the borders survive as
     * fragments and the result reads as {@code "| Server: | !cmds1 | World: | !cmds2"} once a wide
     * row wraps. Splitting on the field separator keeps each field whole.
     *
     * <p>Only when the reply has no compact form does it fall back to the rows, and then every row
     * is kept: losing content to save a message would be a worse trade than sending two.
     */
    static List<String> pack(List<String> rows, String oneLine) {
        List<String> fields = new java.util.ArrayList<>();
        String compact = oneLine == null ? "" : oneLine.strip();
        if (!compact.isEmpty()) {
            for (String field : compact.split("\\s*\\|\\s*")) {
                String part = field.strip();
                if (!part.isEmpty()) fields.add(part);
            }
        } else {
            for (String row : rows) {
                String bare = stripBox(row);
                if (!bare.isEmpty()) fields.add(bare);
            }
        }
        if (fields.isEmpty()) return List.of();

        List<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String field : fields) {
            if (current.length() == 0) {
                current.append(field);
                continue;
            }
            int grown = current.length() + ROW_SEPARATOR.length() + field.length();
            if (grown <= MAX_MESSAGE_CHARS) {
                current.append(ROW_SEPARATOR).append(field);
            } else {
                out.add(current.toString());
                current.setLength(0);
                current.append(field);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return List.copyOf(out);
    }

    /**
     * Removes the border characters a box row is drawn with, leaving its text.
     *
     * <p>{@code │} is the vertical rule, {@code ┌└├} start a row and {@code ┐┘┤} end one. A rule
     * made only of {@code ─} is a divider and has no text at all, so it strips to empty and the
     * caller drops it.
     */
    static String stripBox(String row) {
        if (row == null) return "";
        String bare = row.strip();
        if (!bare.isEmpty() && "│┌└├".indexOf(bare.charAt(0)) >= 0) bare = bare.substring(1);
        if (!bare.isEmpty() && "│┐┘┤".indexOf(bare.charAt(bare.length() - 1)) >= 0) {
            bare = bare.substring(0, bare.length() - 1);
        }
        bare = bare.strip();
        // A heading row is "─ title ────": keep the title, drop the filler.
        bare = bare.replaceAll("^─+\\s*", "").replaceAll("\\s*─+$", "");
        bare = bare.replaceAll("\\s*─{3,}\\s*", " ");
        return bare.strip();
    }
}
