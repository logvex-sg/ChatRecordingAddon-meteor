package com.logvex.addon.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How one status command is configured, and what it renders to.
 *
 * <p>Output mode is the important part. {@link Output#LOCAL} means the answer is shown only in this
 * client — in chat that only the local player sees, or in a notification — and is never transmitted.
 * {@link Output#CHAT} means exactly one message is sent to the server as ordinary chat.
 *
 * <p>There is deliberately no mode that runs a server command. An earlier idea of defaulting to
 * {@code /say} was dropped: {@code /say} is a server command that broadcasts to everyone, so using
 * it for a private status readout would leak the figure and could get the player muted for spam.
 * Chat output is plain chat, and the user chooses whether to enable it.
 */
public record StatusOutput(
    boolean enabled,
    Output output,
    String template,
    String alias
) {
    /** Where a status answer goes. */
    public enum Output {
        /** Shown to this client only; nothing is transmitted. */
        LOCAL("local", "Local only"),
        /** Sent as one ordinary chat message, visible to the server. */
        CHAT("chat", "Send to chat");

        private final String id;
        private final String label;

        Output(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public static Output parse(String value, Output fallback) {
            if (value == null || value.isBlank()) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "chat", "public", "say" -> CHAT;
                case "local", "private", "notify" -> LOCAL;
                default -> fallback;
            };
        }
    }

    /** The default for a command: enabled, local-only, with its shipped template. */
    public static StatusOutput defaults(String command) {
        return new StatusOutput(true, Output.LOCAL,
            StatusCommand.defaultTemplate(command).orElse(""), "");
    }

    public StatusOutput {
        if (output == null) output = Output.LOCAL;
        if (template == null) template = "";
        if (alias == null) alias = "";
        alias = alias.trim().toLowerCase(Locale.ROOT);
    }

    /** The three-field form, for a command that is not renamed. */
    public StatusOutput(boolean enabled, Output output, String template) {
        this(enabled, output, template, "");
    }

    /** True when this command answers to a word other than its canonical name. */
    public boolean renamed() {
        return !alias.isBlank();
    }

    /** True when this command will actually answer. */
    public boolean answers() {
        return enabled;
    }

    /** True when the answer must not leave this client. */
    public boolean localOnly() {
        return output == Output.LOCAL;
    }

    /** True when the answer is sent to the server as chat. */
    public boolean toChat() {
        return output == Output.CHAT;
    }

    /** The rendered text for {@code stats}. */
    public String render(ServerStats stats, String requester) {
        return StatusTemplate.expand(template, stats, requester);
    }

    /** The rendered text for {@code stats}, with the requester's own figures. */
    public String render(ServerStats stats, StatusTemplate.Context context) {
        return StatusTemplate.expand(template, stats, context);
    }

    /**
     * The stored form, {@code enabled|output|template[|alias=word]}, for a settings line.
     *
     * <p>The alias carries an explicit {@code alias=} marker because the template is free text and
     * may itself contain {@code |}: the shipped {@code status} line joins three figures with that
     * character. A bare trailing word could not be told apart from a template's last fragment.
     */
    public String encode() {
        String base = (enabled ? "on" : "off") + "|" + output.id() + "|" + template;
        return alias.isBlank() ? base : base + "|alias=" + alias;
    }

    /** True when {@code segment} is a single word that could name a command. */
    private static boolean looksLikeVerb(String segment) {
        if (segment == null || segment.isBlank()) return false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Parses a stored line, falling back to {@code command}'s defaults for anything missing.
     *
     * <p>One malformed line must not take the whole feature down, so a bad value falls back rather
     * than throwing: a user who hand-edits the configuration gets their command back at its
     * default instead of losing it.
     */
    public static StatusOutput decode(String line, String command) {
        StatusOutput fallback = defaults(command);
        if (line == null || line.isBlank()) return fallback;
        // Split only the two leading fields, then keep the remainder whole: a template may contain
        // the separator itself, and splitting it four ways used to cut every multi-figure line and
        // read the tail as a command alias, which renamed the command to garbage and killed it.
        String rest = line;
        int firstBar = rest.indexOf('|');
        String head = firstBar < 0 ? rest : rest.substring(0, firstBar);
        rest = firstBar < 0 ? "" : rest.substring(firstBar + 1);
        int secondBar = rest.indexOf('|');
        String mode = secondBar < 0 ? rest : rest.substring(0, secondBar);
        rest = secondBar < 0 ? "" : rest.substring(secondBar + 1);

        boolean enabled = fallback.enabled();
        if (!head.isBlank()) {
            enabled = switch (head.trim().toLowerCase(Locale.ROOT)) {
                case "on", "true", "yes", "1", "enabled" -> true;
                case "off", "false", "no", "0", "disabled" -> false;
                default -> fallback.enabled();
            };
        }
        Output output = secondBar < 0 && rest.isEmpty() && mode.isBlank()
            ? fallback.output() : Output.parse(mode, fallback.output());

        String template = rest;
        String alias = "";

        // The explicit marker is unambiguous, so it is read first.
        int marker = rest.lastIndexOf("|alias=");
        if (marker >= 0) {
            String candidate = rest.substring(marker + "|alias=".length()).trim();
            if (looksLikeVerb(candidate)) {
                alias = candidate.toLowerCase(Locale.ROOT);
                template = rest.substring(0, marker);
            }
        } else {
            // A line written by an older version may end in a bare alias word. Accept it only when
            // the tail is a single word, so a template that merely ends after a pipe keeps its text.
            int lastBar = rest.lastIndexOf('|');
            if (lastBar >= 0) {
                String candidate = rest.substring(lastBar + 1).trim();
                if (looksLikeVerb(candidate)) {
                    alias = candidate.toLowerCase(Locale.ROOT);
                    template = rest.substring(0, lastBar);
                }
            }
        }
        template = template.trim();
        if (template.isBlank()) template = fallback.template();
        // A rename that collides with a canonical command would shadow it, so it is dropped rather
        // than allowed to hijack another command's spelling.
        if (!alias.isBlank() && !alias.equals(command) && StatusCommand.find(alias).isPresent()) {
            alias = "";
        }
        return new StatusOutput(enabled, output, template, alias);
    }

    /** The settings for every status command, keyed by canonical name. */
    public static Map<String, StatusOutput> defaultsAll() {
        Map<String, StatusOutput> out = new LinkedHashMap<>();
        for (StatusCommand.Definition command : StatusCommand.all()) {
            out.put(command.name(), defaults(command.name()));
        }
        return Map.copyOf(out);
    }

    /** Every command's settings, encoded one per line, in display order. */
    public static List<String> encodeAll(Map<String, StatusOutput> settings) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (StatusCommand.Definition command : StatusCommand.all()) {
            StatusOutput setting = settings == null ? null : settings.get(command.name());
            out.add(command.name() + "=" + (setting == null
                ? defaults(command.name()) : setting).encode());
        }
        return List.copyOf(out);
    }

    /**
     * Parses stored lines back into a settings map, ignoring any command that is not in the
     * catalogue so an old configuration cannot resurrect a removed command.
     */
    public static Map<String, StatusOutput> decodeAll(List<String> lines) {
        Map<String, StatusOutput> out = new LinkedHashMap<>(defaultsAll());
        if (lines == null) return Map.copyOf(out);
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String name = line.substring(0, split).trim().toLowerCase(Locale.ROOT);
            if (StatusCommand.find(name).isEmpty()) continue;
            out.put(name, decode(line.substring(split + 1), name));
        }
        return Map.copyOf(out);
    }
}
