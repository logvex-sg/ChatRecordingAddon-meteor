package com.logvex.addon.assistant.core;

import com.logvex.addon.stats.StatusCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue of every command the assistant answers in chat.
 *
 * <p>One entry per command, with its aliases and the level it falls back to. Having the list in one
 * place is what makes the permission model sound: the alias table the brain resolves through, the
 * help text, the GUI's command list and the permission defaults are all derived from here, so they
 * cannot drift apart and leave a command whose alias skips its own rule.
 *
 * <p>The default level is deliberately conservative for anything that changes state. A read-only
 * figure such as {@code ping} is harmless for anyone to ask for; {@code remember} writes to shared
 * memory and {@code forgetme} deletes it, so those are local-only until someone opens them up.
 */
public final class CommandCatalog {
    /**
     * One command.
     *
     * @param name        the canonical name, lower case
     * @param aliases     alternative spellings that resolve to this command
     * @param description what it does, for help and the GUI
     * @param defaultLevel the level used when no rule is configured for it
     * @param writes      true when the command changes stored state
     */
    public record Command(String name, List<String> aliases, String description,
                          PermissionLevel defaultLevel, boolean writes) {
    }

    private static final List<Command> COMMANDS = buildCommands();

    /**
     * The catalogue, assembled from the hand-written entries plus the live status commands.
     *
     * <p>The status verbs are generated from {@link StatusCommand} rather than listed again here.
     * That is what keeps the two tables in step: a status command's aliases and its default level
     * are declared once, so adding a figure cannot leave a spelling that the chat layer resolves
     * but the permission layer has never heard of.
     */
    private static List<Command> buildCommands() {
        List<Command> out = new ArrayList<>();
        out.add(new Command("help", List.of("commands", "?"),
            "List the assistant's commands.", PermissionLevel.EVERYONE, false));
        out.add(new Command("about", List.of("assistant"),
            "Assistant state, memory size and reply counters.", PermissionLevel.EVERYONE, false));
        for (StatusCommand.Definition status : StatusCommand.all()) {
            out.add(new Command(status.name(), status.aliases(), status.description(),
                status.sensitive() ? PermissionLevel.LOCAL_ONLY : PermissionLevel.EVERYONE,
                false));
        }
        out.add(new Command("stats", List.of("serverstats"),
            "Every figure the client can see, in one line.", PermissionLevel.EVERYONE, false));
        out.add(new Command("who", List.of("onlineplayers", "list"),
            "The names of the players the client can see.", PermissionLevel.EVERYONE, false));
        out.add(new Command("activity", List.of("chatstats"),
            "How busy chat has been this session.", PermissionLevel.EVERYONE, false));
        out.add(new Command("hello", List.of("hi", "greet"),
            "A greeting that suits the time of day.", PermissionLevel.EVERYONE, false));
        out.add(new Command("gg", List.of("goodgame"),
            "The configured good-game line.", PermissionLevel.EVERYONE, false));
        out.add(new Command("whoami", List.of("me"),
            "What the assistant knows about you.", PermissionLevel.EVERYONE, false));
        out.add(new Command("fact", List.of("get"),
            "Read one stored fact.", PermissionLevel.EVERYONE, false));
        out.add(new Command("facts", List.of("known"),
            "List the stored facts.", PermissionLevel.EVERYONE, false));
        out.add(new Command("memories", List.of("memorystats", "memorysummary"),
            "A summary of everything the assistant remembers.", PermissionLevel.EVERYONE, false));
        out.add(new Command("history", List.of("recent"),
            "The most recent remembered exchanges.", PermissionLevel.WHITELIST, false));
        out.add(new Command("remember", List.of("learn"),
            "Store a fact.", PermissionLevel.LOCAL_ONLY, true));
        out.add(new Command("forget", List.of("unlearn"),
            "Delete a fact.", PermissionLevel.LOCAL_ONLY, true));
        out.add(new Command("forgetme", List.of("clearme"),
            "Delete everything stored about you.", PermissionLevel.LOCAL_ONLY, true));
        out.add(new Command("macros", List.of("shortcuts"),
            "List the enabled chat command macros.", PermissionLevel.EVERYONE, false));
        out.add(new Command("macrohelp", List.of("macro"),
            "One macro's description and syntax.", PermissionLevel.EVERYONE, false));
        out.add(new Command("mod", List.of("mods"),
            "Ping a moderator through the webhook with a report.", PermissionLevel.EVERYONE, true));
        out.add(new Command("report", List.of("alert"),
            "Send a report to the webhook and ping the moderator role.",
            PermissionLevel.EVERYONE, true));
        return List.copyOf(out);
    }

    /** Alias to canonical name, built once from {@link #COMMANDS}. */
    private static final Map<String, String> ALIASES = buildAliases();

    /** Canonical name to declared default level, built once from {@link #COMMANDS}. */
    private static final Map<String, PermissionLevel> LEVELS = buildLevels();

    private CommandCatalog() {
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Command command : COMMANDS) {
            map.put(command.name(), command.name());
            for (String alias : command.aliases()) {
                map.putIfAbsent(alias.toLowerCase(Locale.ROOT), command.name());
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, PermissionLevel> buildLevels() {
        Map<String, PermissionLevel> map = new LinkedHashMap<>();
        for (Command command : COMMANDS) map.put(command.name(), command.defaultLevel());
        return Map.copyOf(map);
    }

    /**
     * The declared default level for {@code command}, or null when it is not a known command.
     *
     * <p>This is what makes the {@code defaultLevel} on a catalogue entry mean something: a command
     * with no configured rule is gated at the level it was declared with, so a sensitive status
     * verb is local-only out of the box instead of open to every stranger who guesses the name.
     */
    public static PermissionLevel defaultLevelFor(String command) {
        if (command == null) return null;
        return LEVELS.get(command.trim().toLowerCase(Locale.ROOT));
    }

    /** Every command, in help order. */
    public static List<Command> all() {
        return COMMANDS;
    }

    /** The alias table, for {@link PermissionSet#canonical}. */
    public static Map<String, String> aliases() {
        return ALIASES;
    }

    /** The canonical name for {@code word}, or empty when it is not a known command or alias. */
    public static Optional<String> canonical(String word) {
        if (word == null) return Optional.empty();
        return Optional.ofNullable(ALIASES.get(word.trim().toLowerCase(Locale.ROOT)));
    }

    /** The first alias of {@code command}, or null when it has none. */
    public static String aliasOf(String command) {
        Optional<Command> found = find(command);
        if (found.isEmpty()) return null;
        List<String> aliases = found.get().aliases();
        return aliases.isEmpty() ? null : aliases.get(0);
    }

    /** The definition of {@code command}, matched on its canonical name. */
    public static Optional<Command> find(String command) {
        if (command == null) return Optional.empty();
        String key = command.trim().toLowerCase(Locale.ROOT);
        for (Command candidate : COMMANDS) {
            if (candidate.name().equals(key)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Every canonical name, in help order. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(COMMANDS.size());
        for (Command command : COMMANDS) out.add(command.name());
        return List.copyOf(out);
    }

    /** The commands that change stored state. */
    public static List<Command> writingCommands() {
        List<Command> out = new ArrayList<>();
        for (Command command : COMMANDS) if (command.writes()) out.add(command);
        return List.copyOf(out);
    }
}
