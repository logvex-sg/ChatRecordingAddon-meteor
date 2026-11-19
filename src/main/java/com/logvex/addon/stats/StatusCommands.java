package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Answers the live status commands.
 *
 * <p>This replaces the older free-form stats wording with a template-driven renderer, so every
 * command has one configurable output shape instead of a sentence hard-coded in the brain. The
 * class decides <em>what</em> the text is and <em>where</em> it goes; the brain only carries the
 * result to the chat layer.
 *
 * <p>Nothing here touches Minecraft or the network, which is what makes the whole feature testable:
 * a test can assert that local mode produces text without sending anything, and that chat mode
 * produces exactly one line.
 */
public final class StatusCommands {
    /**
     * The settings the dispatcher reads.
     *
     * @param enabled  master switch for every status command
     * @param commands per-command settings, keyed by canonical name
     */
    public record Settings(boolean enabled, Map<String, StatusOutput> commands) {
        public static final Settings DEFAULTS =
            new Settings(true, StatusOutput.defaultsAll());

        public Settings {
            if (commands == null || commands.isEmpty()) {
                commands = StatusOutput.defaultsAll();
            } else {
                // Fill in anything the stored map is missing, so a new command ships enabled
                // rather than silently absent for existing users.
                Map<String, StatusOutput> merged = new java.util.LinkedHashMap<>(
                    StatusOutput.defaultsAll());
                merged.putAll(commands);
                commands = Map.copyOf(merged);
            }
        }

        /** The setting for {@code command}, never null. */
        public StatusOutput forCommand(String command) {
            StatusOutput setting = commands.get(command);
            return setting == null ? StatusOutput.defaults(command) : setting;
        }

        /**
         * The word each command actually answers to, keyed by canonical name.
         *
         * <p>A renamed command maps to its new spelling and everything else maps to itself, so a
         * caller can resolve a typed word back to the canonical command and describe the rename in
         * help without a second lookup.
         */
        public Map<String, String> verbs() {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (StatusCommand.Definition command : StatusCommand.all()) {
                StatusOutput setting = forCommand(command.name());
                out.put(command.name(), setting.renamed() ? setting.alias() : command.name());
            }
            return Map.copyOf(out);
        }
    }

    /**
     * One rendered answer.
     *
     * @param command  the canonical command that produced it
     * @param text     the rendered template, already expanded
     * @param lines    {@code text} wrapped to a readable width, for local display
     * @param output   where the answer is allowed to go
     * @param sensitive true when the answer describes the player rather than the server
     */
    public record Answer(String command, String text, List<String> lines,
                         StatusOutput.Output output, boolean sensitive) {
        public Answer {
            if (text == null) text = "";
            if (lines == null) lines = List.of();
            lines = List.copyOf(lines);
            if (output == null) output = StatusOutput.Output.LOCAL;
        }

        /** True when this answer must never be transmitted to the server. */
        public boolean localOnly() {
            return output == StatusOutput.Output.LOCAL || sensitive;
        }
    }

    /** The width local multi-line output wraps to. */
    public static final int LOCAL_WIDTH = 52;

    private StatusCommands() {
    }

    /**
     * The answer to {@code body}, or empty when no status command matches or it is switched off.
     *
     * @param body      the text after the command prefix
     * @param stats     the current snapshot
     * @param settings  the configured per-command settings
     * @param requester the name of the player who asked, for {@code {player}}
     */
    public static Optional<Answer> answer(String body, ServerStats stats, Settings settings,
                                          String requester) {
        return answer(body, stats, settings, new StatusTemplate.Context(requester, -1));
    }

    /**
     * The answer to {@code body}, with the requester's own figures.
     *
     * @param context the requester's name and latency, so {@code !ping} answers about them
     */
    public static Optional<Answer> answer(String body, ServerStats stats, Settings settings,
                                          StatusTemplate.Context context) {
        if (body == null || body.isBlank()) return Optional.empty();
        Settings opts = settings == null ? Settings.DEFAULTS : settings;
        if (!opts.enabled()) return Optional.empty();

        String verb = body.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        Optional<String> canonical = canonicalFor(verb, opts);
        if (canonical.isEmpty()) return Optional.empty();

        String command = canonical.get();
        StatusOutput setting = opts.forCommand(command);
        if (!setting.answers()) return Optional.empty();

        ServerStats live = stats == null ? ServerStats.OFFLINE : stats;
        String text = setting.render(live, context).strip();
        if (text.isEmpty()) return Optional.empty();

        boolean sensitive = StatusCommand.find(command)
            .map(StatusCommand.Definition::sensitive).orElse(false);
        return Optional.of(new Answer(command, text, wrap(text, LOCAL_WIDTH),
            setting.output(), sensitive));
    }

    /**
     * The canonical command a typed word refers to, honouring any configured rename.
     *
     * <p>A renamed command answers to its new spelling only: the old word stops resolving, which is
     * what a rename means. Everything else resolves through the catalogue's own alias table.
     */
    private static Optional<String> canonicalFor(String verb, Settings settings) {
        Map<String, String> verbs = settings.verbs();
        for (Map.Entry<String, String> entry : verbs.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(verb)) return Optional.of(entry.getKey());
        }
        Optional<String> catalogue = StatusCommand.canonical(verb);
        if (catalogue.isEmpty()) return Optional.empty();
        // A renamed command answers only to its new name: leaving the old spelling live would give
        // one command two words and make the rename advisory rather than effective.
        String command = catalogue.get();
        return verbs.getOrDefault(command, command).equalsIgnoreCase(command)
            ? catalogue : Optional.empty();
    }

    /** Wraps {@code text} on spaces so a long answer stays readable in the chat box. */
    public static List<String> wrap(String text, int width) {
        if (text == null || text.isEmpty()) return List.of();
        int limit = Math.max(16, width);
        if (text.length() <= limit) return List.of(text);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > limit) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return List.copyOf(out);
    }

    /**
     * A preview of what chat mode would send, for the settings screen.
     *
     * <p>Uses the live snapshot so the preview shows the real figures rather than a mock-up, and
     * reports the same text the player would actually transmit.
     */
    public static String preview(String command, ServerStats stats, String requester) {
        String template = StatusCommand.defaultTemplate(command).orElse("");
        return StatusTemplate.expand(template, stats, requester);
    }

    /** The verbs this class can answer, for the help text. */
    public static String usage() {
        return String.join(", ", StatusCommand.names());
    }

    /**
     * The names of the players the client can see, or a sentence saying why there are none.
     *
     * <p>Names come from the tab list, so a player who has never spoken is still listed. On a server
     * that hides players from the tab list this shows fewer names than are really online; the wording
     * says "visible" so the figure is never read as the server's own count.
     */
    public static String who(ServerStats stats) {
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
        if (!s.inWorld()) return "Not connected.";
        List<String> online = s.onlinePlayers();
        if (online.isEmpty()) {
            int count = s.players();
            if (count > 0) {
                return count + (count == 1 ? " player is" : " players are")
                    + " online, but the server hides their names.";
            }
            return "No players visible in the tab list.";
        }
        return online.size() + (online.size() == 1 ? " player" : " players") + ": "
            + String.join(", ", online) + ".";
    }

    /**
     * The visible players grouped into lines that fit {@code width}.
     *
     * <p>Returned as a list rather than one string so the caller can put them in an embed field and
     * have each line stay inside the block's border.
     */
    public static List<String> whoLines(ServerStats stats, int width) {
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
        if (!s.inWorld()) return List.of("Not connected.");
        List<String> online = s.onlinePlayers();
        if (online.isEmpty()) return List.of(who(s));
        int limit = Math.max(16, width);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String name : online) {
            if (line.length() > 0 && line.length() + 2 + name.length() > limit) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(", ");
            line.append(name);
        }
        if (line.length() > 0) out.add(line.toString());
        return List.copyOf(out);
    }
}
