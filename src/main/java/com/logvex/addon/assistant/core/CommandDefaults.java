package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The shipped settings for every prefix command.
 *
 * <p>The status commands already had a table. Everything else — {@code !hello}, {@code !gg},
 * {@code !who}, the memory commands — was either hard-wired or only reachable through a single
 * global switch, which meant a user who wanted a different greeting had to edit the source and one
 * who wanted to silence {@code !who} had no way to say so.
 *
 * <p>This class gives every prefix command one line in one table, in the same shape the status
 * commands use, so the whole surface is configured in one place:
 *
 * <pre>name = on|off | local|chat|silent | template</pre>
 *
 * <p>A template is optional. When it is blank the command keeps its built-in wording, which is the
 * useful default: the table exists to <em>allow</em> customisation, not to force every user to
 * restate what already works.
 *
 * <p>Templates are expanded with the same placeholder language as the rest of the assistant, so
 * {@code {player}}, {@code {server}}, {@code {ping}} and the rest all work. A line that cannot be
 * parsed is skipped rather than guessed at, so one typo cannot silence a command or make it shout.
 */
public final class CommandDefaults {
    /** Where an answer is allowed to go. */
    public enum Output {
        /** Shown in this client only; never transmitted. */
        LOCAL,
        /** Sent as one ordinary chat line. */
        CHAT,
        /** Answered internally, with nothing visible. */
        SILENT;

        public static Optional<Output> parse(String word) {
            if (word == null) return Optional.empty();
            return switch (word.trim().toLowerCase(Locale.ROOT)) {
                case "local", "notify" -> Optional.of(LOCAL);
                case "chat" -> Optional.of(CHAT);
                case "silent", "off" -> Optional.of(SILENT);
                default -> Optional.empty();
            };
        }

        /** The word this mode is written as, for the stored form. */
        public String token() {
            return switch (this) {
                case LOCAL -> "local";
                case CHAT -> "chat";
                case SILENT -> "silent";
            };
        }
    }

    /**
     * One command's shipped configuration.
     *
     * @param name     the canonical command name
     * @param enabled  false makes the command answer nothing at all
     * @param output   where its answer may go
     * @param template the wording to use, or blank to keep the built-in text
     */
    public record Entry(String name, boolean enabled, Output output, String template) {
        public Entry {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
            if (output == null) output = Output.LOCAL;
            template = template == null ? "" : template.strip();
        }

        /** True when this entry asks for custom wording. */
        public boolean customised() {
            return !template.isBlank();
        }

        /** The stored form, in the same shape the status table uses. */
        public String encode() {
            return name + " = " + (enabled ? "on" : "off") + " | " + output.token() + " | "
                + template;
        }
    }

    /**
     * The shipped table.
     *
     * <p>Read-only commands default to {@link Output#LOCAL}: a chat answer to {@code !status} is a
     * message the whole server sees, and most people asking their own client a question do not mean
     * to broadcast it. Commands whose whole purpose is public — {@code !hello}, {@code !gg} — default
     * to chat. The state-changing memory commands stay local, because their answers quote stored text.
     */
    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (CommandCatalog.Command command : CommandCatalog.all()) {
            out.add(new Entry(command.name(), true, defaultOutput(command.name()), ""));
        }
        return List.copyOf(out);
    }

    /**
     * The output mode a command ships with.
     *
     * <p>Explicit rather than inferred from the catalogue's permission level: a level says who may
     * run a command, which is a different question from whether the answer should be heard by the
     * server. Keeping them separate means opening a command to other players cannot accidentally
     * make this client broadcast its own figures.
     */
    public static Output defaultOutput(String name) {
        if (name == null) return Output.LOCAL;
        return switch (name.toLowerCase(Locale.ROOT)) {
            // Public by intent: these exist to be seen.
            case "hello", "gg" -> Output.CHAT;
            // Everything else answers the local player.
            default -> Output.LOCAL;
        };
    }

    /** The shipped table as a map keyed by command name. */
    public static Map<String, Entry> defaults() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry entry : entries()) out.put(entry.name(), entry);
        return Map.copyOf(out);
    }

    /** Every entry in the stored form, one line each. */
    public static List<String> encoded() {
        return entries().stream().map(Entry::encode).toList();
    }

    /**
     * Parses one stored line.
     *
     * <p>Accepts {@code name = on|local|template} with the mode and template optional, so a short
     * line such as {@code who = off} does the obvious thing. Returns empty rather than throwing:
     * this reads hand-edited text, and one bad line must not break the rest of the table.
     */
    public static Optional<Entry> decode(String line) {
        if (line == null) return Optional.empty();
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) return Optional.empty();

        int equals = text.indexOf('=');
        String name;
        String body;
        if (equals < 0) {
            name = text;
            body = "";
        } else {
            name = text.substring(0, equals).strip();
            body = text.substring(equals + 1).strip();
        }
        if (name.isEmpty() || name.indexOf(' ') >= 0) return Optional.empty();

        String[] fields = body.split("\\|", -1);
        boolean enabled = true;
        Output output = defaultOutput(name);
        String template = "";

        if (fields.length > 0) {
            String first = fields[0].strip().toLowerCase(Locale.ROOT);
            if (first.equals("on")) {
                enabled = true;
            } else if (first.equals("off")) {
                enabled = false;
            } else if (!first.isEmpty()) {
                // A first field that is neither on nor off is the start of the template, so a user
                // who writes `who = Players: {playercount}` is not silently ignored.
                template = body;
            }
        }
        if (template.isEmpty() && fields.length > 1) {
            Optional<Output> parsed = Output.parse(fields[1]);
            if (parsed.isPresent()) {
                output = parsed.get();
                if (fields.length > 2) {
                    // Everything after the mode is the template, including any further pipes: a
                    // template is free text and may well contain a separator of its own.
                    template = body.substring(body.indexOf('|') + 1).strip();
                    int modeEnd = template.indexOf('|');
                    if (modeEnd >= 0) template = template.substring(modeEnd + 1).strip();
                }
            } else {
                // Not a mode word, so treat the rest as the template and keep the default mode.
                template = body.substring(body.indexOf('|') + 1).strip();
            }
        }
        return Optional.of(new Entry(name, enabled, output, template));
    }

    /**
     * The table parsed from stored lines, with anything missing filled in from the shipped defaults.
     *
     * <p>Filling in matters because the catalogue grows: a command added in a later version must
     * arrive enabled rather than absent just because an older saved table does not mention it.
     */
    public static Map<String, Entry> parseAll(List<String> lines) {
        Map<String, Entry> out = new LinkedHashMap<>(defaults());
        if (lines == null) return Map.copyOf(out);
        for (String line : lines) {
            decode(line).ifPresent(entry -> out.put(entry.name(), entry));
        }
        return Map.copyOf(out);
    }

    private CommandDefaults() {
    }
}
