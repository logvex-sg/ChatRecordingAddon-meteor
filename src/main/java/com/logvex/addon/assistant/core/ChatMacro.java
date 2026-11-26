package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One chat command macro: a shortcut the player types, and the server command it becomes.
 *
 * <p>This is deliberately a different thing from a {@link TriggerRule}. A trigger answers
 * <em>incoming</em> chat; a macro rewrites something the <em>local player</em> typed. Keeping them in
 * separate types is what stops one being mistaken for the other: a macro can never be reached by
 * another player's message, and a trigger can never send a command.
 *
 * <p>The configuration is one line, written the way it reads:
 *
 * <pre>{@code
 * !tpa <player> => /tpa {player}
 * !spawn => /spawn
 * !msg <player> <message> => /msg {player} {message}
 * }</pre>
 *
 * <p>The part before {@code =>} is the usage: a shortcut, then its arguments in angle brackets. The
 * part after is the command template, with placeholders in braces. Everything else — cooldowns,
 * sounds, reply modes — is not configurable here on purpose. A macro that sends a command needs none
 * of it, and asking for it would bury the two fields that matter.
 *
 * <p>Argument placeholders:
 * <ul>
 *   <li>{@code {player}} — the first argument</li>
 *   <li>{@code {name}} — a named argument, by position</li>
 *   <li>{@code {amount}} — a numeric argument</li>
 *   <li>{@code {message}} — every remaining argument, joined with single spaces</li>
 * </ul>
 *
 * <p>A {@code {message}} argument must be the last one declared, because it consumes the rest of the
 * line. Arguments written as {@code [name]} are optional: a missing one is left blank rather than
 * refusing the whole command, and the template simply omits that placeholder.
 */
public record ChatMacro(
    String shortcut,
    String usage,
    String template,
    String description,
    boolean enabled,
    List<String> aliases,
    boolean remote
) {
    /** A macro that is not shared with other players, which is the default for every macro. */
    public ChatMacro(String shortcut, String usage, String template, String description,
                     boolean enabled, List<String> aliases) {
        this(shortcut, usage, template, description, enabled, aliases, false);
    }
    /** The separator between the usage and the command template. */
    public static final String ARROW = "=>";

    /** The placeholder that absorbs every remaining argument. */
    public static final String MESSAGE = "message";

    /**
     * The result of trying to run a macro: either a command to send, or a usage line to show.
     *
     * <p>There is no third state. A macro that cannot produce a well-formed command always explains
     * itself instead, so a half-substituted template can never reach the server.
     */
    public record Outcome(boolean ok, String command, String usageLine, String problem) {
        static Outcome sent(String command) {
            return new Outcome(true, command, "", "");
        }

        static Outcome usage(String usageLine, String problem) {
            return new Outcome(false, "", usageLine, problem);
        }
    }

    /** One declared argument of a usage string. */
    public record Arg(String name, boolean optional, boolean rest) {
    }

    public ChatMacro {
        if (shortcut == null) shortcut = "";
        shortcut = shortcut.strip();
        if (usage == null || usage.isBlank()) usage = shortcut;
        usage = usage.strip();
        if (template == null) template = "";
        template = template.strip();
        if (description == null) description = "";
        if (aliases == null) aliases = List.of();
    }

    /** Every shortcut this macro answers to: the shortcut itself, then its aliases. */
    public List<String> triggers() {
        List<String> out = new ArrayList<>(1 + aliases.size());
        if (!shortcut.isBlank()) out.add(shortcut);
        for (String alias : aliases) {
            String trimmed = alias == null ? "" : alias.strip();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(shortcut)) continue;
            if (out.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed))) continue;
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /** The declared arguments, in order, read from the usage string. */
    public List<Arg> args() {
        return parseArgs(usage);
    }

    /**
     * True when this macro is runnable: it has a shortcut, a template and a well-formed usage.
     *
     * <p>A template that does not start with {@code /} is refused. Sending it would post the text as
     * public chat, which is the opposite of what a command macro is for, and would also risk echoing
     * the shortcut back into chat.
     */
    public boolean valid() {
        if (shortcut.isBlank() || template.isBlank()) return false;
        if (!template.startsWith("/")) return false;
        List<Arg> declared = args();
        // {message} consumes the remainder, so anything after it could never be filled.
        for (int i = 0; i < declared.size(); i++) {
            if (declared.get(i).rest() && i != declared.size() - 1) return false;
        }
        return true;
    }

    /** The one-line usage shown by {@code !macros}, {@code !macrohelp} and a failed run. */
    public String usageLine() {
        return args().isEmpty() ? shortcut : usage;
    }

    /**
     * Binds {@code input} (the text after the shortcut) to this macro's arguments and expands the
     * template.
     *
     * @param input everything the player typed after the shortcut, without the leading space
     * @return the command to send, or a usage line explaining what was missing
     */
    public Outcome run(String input) {
        if (!valid()) {
            return Outcome.usage(usageLine(),
                "This macro is not configured correctly, so nothing was sent.");
        }
        List<String> words = tokenize(input);
        List<Arg> declared = args();

        Map<String, String> values = new LinkedHashMap<>();
        int cursor = 0;
        for (Arg arg : declared) {
            if (arg.rest()) {
                String joined = String.join(" ", words.subList(Math.min(cursor, words.size()), words.size()));
                values.put(arg.name(), joined);
                cursor = words.size();
                break;
            }
            if (cursor < words.size()) {
                values.put(arg.name(), words.get(cursor++));
            } else if (!arg.optional()) {
                return Outcome.usage(usageLine(), "Missing argument: " + arg.name() + ".");
            } else {
                // Bound to empty rather than left unset: the template still names this placeholder,
                // and an absent entry would be reported as an unknown one.
                values.put(arg.name(), "");
            }
        }

        // Extra words with nowhere to go would be silently dropped, which turns a typo into a
        // command that does something other than what was typed. Refuse instead.
        if (cursor < words.size()) {
            return Outcome.usage(usageLine(), "Too many arguments.");
        }

        return expand(values);
    }

    /** Substitutes {@code values} into the template, refusing anything left unbound. */
    private Outcome expand(Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                return Outcome.usage(usageLine(), "This macro has an unclosed placeholder.");
            }
            String name = template.substring(i + 1, close).strip().toLowerCase(Locale.ROOT);
            String value = values.get(name);
            if (value == null) {
                return Outcome.usage(usageLine(), "Unknown placeholder: {" + name + "}.");
            }
            if (value.isBlank() && !isOptional(name)) {
                return Outcome.usage(usageLine(), "Missing argument: " + name + ".");
            }
            out.append(value);
            i = close + 1;
        }

        String command = collapseSpaces(out.toString()).strip();
        if (command.length() <= 1) {
            return Outcome.usage(usageLine(), "This macro produced an empty command.");
        }
        // A newline would split one command into two, and the server would run the second half as
        // whatever it happened to be. Macros are one line by construction.
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return Outcome.usage(usageLine(), "This macro produced more than one line.");
        }
        return Outcome.sent(command);
    }

    private boolean isOptional(String name) {
        for (Arg arg : args()) {
            if (arg.name().equals(name)) return arg.optional();
        }
        return false;
    }

    /** Collapses runs of spaces left behind by an omitted optional argument. */
    private static String collapseSpaces(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean lastSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                if (!lastSpace) out.append(c);
                lastSpace = true;
            } else {
                out.append(c);
                lastSpace = false;
            }
        }
        return out.toString();
    }

    /** Splits on whitespace, discarding empty tokens. */
    static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String token : input.strip().split("\\s+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    /**
     * Reads the arguments out of a usage string such as {@code !msg <player> <message>}.
     *
     * <p>The first token is the shortcut and is skipped. {@code <name>} is required, {@code [name]} is
     * optional, and a name ending in {@code ...} or named {@code message} absorbs the rest.
     */
    static List<Arg> parseArgs(String usage) {
        List<String> tokens = tokenize(usage);
        if (tokens.size() <= 1) return List.of();
        List<Arg> out = new ArrayList<>(tokens.size() - 1);
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean optional;
            String name;
            if (token.startsWith("<") && token.endsWith(">")) {
                optional = false;
                name = token.substring(1, token.length() - 1);
            } else if (token.startsWith("[") && token.endsWith("]")) {
                optional = true;
                name = token.substring(1, token.length() - 1);
            } else {
                continue;
            }
            name = name.strip().toLowerCase(Locale.ROOT);
            boolean rest = name.endsWith("...") || name.equals(MESSAGE);
            if (name.endsWith("...")) name = name.substring(0, name.length() - 3);
            if (name.isEmpty()) continue;
            out.add(new Arg(name, optional, rest));
        }
        return List.copyOf(out);
    }

    /** Parses one configuration line, or returns null when it is not a macro at all. */
    public static ChatMacro parse(String line) {
        if (line == null) return null;
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) return null;
        int arrow = text.indexOf(ARROW);
        if (arrow < 0) return null;
        String usage = text.substring(0, arrow).strip();
        String template = text.substring(arrow + ARROW.length()).strip();
        if (usage.isEmpty() || template.isEmpty()) return null;

        String shortcut = usage.split("\\s+", 2)[0];
        return new ChatMacro(shortcut, usage, template, "", true, List.of());
    }

    /** The storage form: the same one-line syntax the user writes. */
    public String encode() {
        StringBuilder out = new StringBuilder();
        out.append(usage).append(' ').append(ARROW).append(' ').append(template);
        if (!description.isBlank()) out.append(" ; ").append(description);
        if (!enabled) out.append(" ; off");
        if (!aliases.isEmpty()) out.append(" ; aliases=").append(String.join(",", aliases));
        if (remote) out.append(" ; remote");
        return out.toString();
    }

    /**
     * Reads a stored line back.
     *
     * <p>The trailing {@code ;} segments are optional, so a line the user typed by hand — just the
     * usage and the template — loads exactly as written.
     */
    public static ChatMacro decode(String line) {
        if (line == null) return null;
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) return null;

        String description = "";
        boolean enabled = true;
        boolean remote = false;
        List<String> aliases = List.of();

        int arrow = text.indexOf(ARROW);
        if (arrow < 0) return null;

        // Split the tail off first: a description may legitimately contain ';' only if the user
        // escaped nothing, so the segments after the template are read from the right.
        String head = text;
        String[] segments = text.split(";");
        if (segments.length > 1) {
            head = segments[0].strip();
            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i].strip();
                String lower = segment.toLowerCase(Locale.ROOT);
                if (lower.equals("off") || lower.equals("disabled")) {
                    enabled = false;
                } else if (lower.equals("remote") || lower.equals("shared")) {
                    remote = true;
                } else if (lower.startsWith("aliases=")) {
                    String list = segment.substring("aliases=".length()).strip();
                    List<String> parsed = new ArrayList<>();
                    for (String alias : list.split(",")) {
                        String trimmed = alias.strip();
                        if (!trimmed.isEmpty()) parsed.add(trimmed);
                    }
                    aliases = List.copyOf(parsed);
                } else if (lower.startsWith("desc=")) {
                    description = segment.substring("desc=".length()).strip();
                } else if (!segment.isEmpty()) {
                    description = segment;
                }
            }
        }

        int headArrow = head.indexOf(ARROW);
        if (headArrow < 0) return null;
        String usage = head.substring(0, headArrow).strip();
        String template = head.substring(headArrow + ARROW.length()).strip();
        if (usage.isEmpty() || template.isEmpty()) return null;

        String shortcut = usage.split("\\s+", 2)[0];
        return new ChatMacro(shortcut, usage, template, description, enabled, aliases, remote);
    }

    /** A copy with different values, so the GUI can edit a macro without mutating the original. */
    public ChatMacro with(String shortcut, String usage, String template, String description,
                          boolean enabled, List<String> aliases) {
        return new ChatMacro(shortcut, usage, template, description, enabled, aliases, remote);
    }

    /** A copy shared with other players, or not. */
    public ChatMacro withRemote(boolean shared) {
        return new ChatMacro(shortcut, usage, template, description, enabled, aliases, shared);
    }

    /**
     * The macros offered on first run.
     *
     * <p>These are examples of what a server command looks like, not a claim that every server has
     * them. They are editable and deletable, and restoring the defaults brings them back.
     */
    public static List<ChatMacro> defaults() {
        List<ChatMacro> out = new ArrayList<>();
        out.add(macro("!tpa <player>", "/tpa {player}", "Ask to teleport to a player."));
        out.add(macro("!tpaccept", "/tpaccept", "Accept a pending teleport request."));
        out.add(macro("!tpdeny", "/tpdeny", "Refuse a pending teleport request."));
        out.add(macro("!spawn", "/spawn", "Return to the server spawn."));
        out.add(macro("!home <name>", "/home {name}", "Teleport to one of your homes."));
        out.add(macro("!sethome <name>", "/sethome {name}", "Save a home at your position."));
        out.add(macro("!delhome <name>", "/delhome {name}", "Delete a saved home."));
        out.add(macro("!back", "/back", "Return to where you were before."));
        out.add(macro("!warp <name>", "/warp {name}", "Teleport to a server warp."));
        out.add(macro("!msg <player> <message>", "/msg {player} {message}", "Send a private message."));
        out.add(macro("!r <message>", "/r {message}", "Reply to the last private message."));
        out.add(macro("!bal", "/bal", "Show your balance."));
        out.add(macro("!pay <player> <amount>", "/pay {player} {amount}", "Pay a player."));
        out.add(macro("!rules", "/rules", "Show the server rules."));
        out.add(macro("!discord", "/discord", "Show the server's Discord invite."));
        return List.copyOf(out);
    }

    private static ChatMacro macro(String usage, String template, String description) {
        return new ChatMacro(usage.split("\\s+", 2)[0], usage, template, description, true, List.of());
    }

    /**
     * Finds the macro that answers to {@code shortcut}, case-insensitively.
     *
     * <p>Aliases are searched too, so a user can give one macro several spellings. The first match
     * wins, and the caller is expected to reject a configuration with two macros claiming the same
     * spelling.
     */
    public static Optional<ChatMacro> find(List<ChatMacro> macros, String shortcut) {
        if (macros == null || shortcut == null || shortcut.isBlank()) return Optional.empty();
        String wanted = MacroSet.bare(shortcut);
        for (ChatMacro macro : macros) {
            for (String trigger : macro.triggers()) {
                if (MacroSet.bare(trigger).equals(wanted)) return Optional.of(macro);
            }
        }
        return Optional.empty();
    }

    /**
     * Every spelling claimed by more than one macro.
     *
     * <p>Two macros on the same spelling is ambiguous: whichever came first would always win and the
     * other could never be reached. Reporting them lets the GUI and a test refuse the configuration
     * instead of silently hiding a macro.
     */
    public static List<String> duplicates(List<ChatMacro> macros) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (macros != null) {
            for (ChatMacro macro : macros) {
                for (String trigger : macro.triggers()) {
                    counts.merge(trigger.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
        }
        List<String> out = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) out.add(name);
        });
        return List.copyOf(out);
    }
}
