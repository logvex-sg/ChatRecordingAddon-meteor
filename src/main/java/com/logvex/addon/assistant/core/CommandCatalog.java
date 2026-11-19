package com.logvex.addon.assistant.core;

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

    private static final List<Command> COMMANDS = List.of(
        new Command("help", List.of("commands", "?"),
            "List the assistant's commands.", PermissionLevel.EVERYONE, false),
        new Command("status", List.of("about"),
            "Assistant state, memory size and reply counters.", PermissionLevel.EVERYONE, false),
        new Command("stats", List.of("serverstats"),
            "Server and player figures in one line.", PermissionLevel.EVERYONE, false),
        new Command("ping", List.of("latency", "ms"),
            "Your latency and a verdict.", PermissionLevel.EVERYONE, false),
        new Command("tps", List.of("tickrate"),
            "The server's tick rate.", PermissionLevel.EVERYONE, false),
        new Command("coords", List.of("pos", "position"),
            "Your position and dimension.", PermissionLevel.EVERYONE, false),
        new Command("health", List.of("hp"),
            "Your health and hunger.", PermissionLevel.EVERYONE, false),
        new Command("fps", List.of("frames"),
            "The client's frame rate.", PermissionLevel.EVERYONE, false),
        new Command("server", List.of("host"),
            "The server name and session length.", PermissionLevel.EVERYONE, false),
        new Command("time", List.of("clock"),
            "The in-game clock.", PermissionLevel.EVERYONE, false),
        new Command("players", List.of("playercount", "online"),
            "How many players are visible.", PermissionLevel.EVERYONE, false),
        new Command("who", List.of("onlineplayers", "list"),
            "The names of the players the client can see.", PermissionLevel.EVERYONE, false),
        new Command("uptime", List.of("session"),
            "How long this session has been connected.", PermissionLevel.EVERYONE, false),
        new Command("world", List.of("dimension", "biome"),
            "The world and dimension you are in.", PermissionLevel.EVERYONE, false),
        new Command("speed", List.of("velocity"),
            "Your movement speed.", PermissionLevel.EVERYONE, false),
        new Command("activity", List.of("chatstats"),
            "How busy chat has been this session.", PermissionLevel.EVERYONE, false),
        new Command("hello", List.of("hi", "greet"),
            "A greeting that suits the time of day.", PermissionLevel.EVERYONE, false),
        new Command("gg", List.of("goodgame"),
            "The configured good-game line.", PermissionLevel.EVERYONE, false),
        new Command("whoami", List.of("me"),
            "What the assistant knows about you.", PermissionLevel.EVERYONE, false),
        new Command("fact", List.of("get"),
            "Read one stored fact.", PermissionLevel.EVERYONE, false),
        new Command("facts", List.of("known"),
            "List the stored facts.", PermissionLevel.EVERYONE, false),
        new Command("memory", List.of("memorystats"),
            "A summary of everything remembered.", PermissionLevel.EVERYONE, false),
        new Command("history", List.of("recent"),
            "The most recent remembered exchanges.", PermissionLevel.WHITELIST, false),
        new Command("remember", List.of("learn"),
            "Store a fact.", PermissionLevel.LOCAL_ONLY, true),
        new Command("forget", List.of("unlearn"),
            "Delete a fact.", PermissionLevel.LOCAL_ONLY, true),
        new Command("forgetme", List.of("clearme"),
            "Delete everything stored about you.", PermissionLevel.LOCAL_ONLY, true),
        new Command("macros", List.of("shortcuts"),
            "List the enabled chat command macros.", PermissionLevel.EVERYONE, false),
        new Command("macrohelp", List.of("macro"),
            "One macro's description and syntax.", PermissionLevel.EVERYONE, false),
        new Command("mod", List.of("mods"),
            "Ping a moderator through the webhook with a report.", PermissionLevel.EVERYONE, true),
        new Command("report", List.of("alert"),
            "Send a report to the webhook and ping the moderator role.",
            PermissionLevel.EVERYONE, true)
    );

    /** Alias to canonical name, built once from {@link #COMMANDS}. */
    private static final Map<String, String> ALIASES = buildAliases();

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
