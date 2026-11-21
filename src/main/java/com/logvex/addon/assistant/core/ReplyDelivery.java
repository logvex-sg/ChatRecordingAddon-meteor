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
                : rows.size() == 1
                    ? new Plan(Mode.SERVER_ONE_LINE, rows)
                    : new Plan(Mode.SERVER_PACED_LINES, rows);
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
}
