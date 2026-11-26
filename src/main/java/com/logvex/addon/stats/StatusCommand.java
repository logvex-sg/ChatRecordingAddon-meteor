package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue of live status commands: one entry per figure the client can report.
 *
 * <p>Keeping the table in one place is what makes the feature configurable without becoming
 * incoherent. The verb a player types, the template that renders it, the setting that switches it
 * off and the permission that gates it are all derived from a single entry, so a command cannot end
 * up half-wired — visible in help but unrouted, or routed but missing from the settings screen.
 *
 * <p>Each entry carries a {@code sensitive} flag. A sensitive command exposes something about the
 * <em>person</em> rather than the server: where they are standing, what they are looking at, or the
 * real-world clock. Those default to local-only, so a stranger asking {@code !coords} is refused
 * rather than told where the local player is. The flag also keeps them out of any shared webhook.
 */
public final class StatusCommand {
    /**
     * One status command.
     *
     * @param name        the canonical verb, lower case
     * @param aliases     alternative spellings that resolve here
     * @param description what it reports, for help and the settings screen
     * @param template    the default output template, using {@link StatusTemplate} placeholders
     * @param sensitive   true when the output is about the player rather than the server
     * @param enabled     true when it answers out of the box
     */
    public record Definition(
        String name,
        List<String> aliases,
        String description,
        String template,
        boolean sensitive,
        boolean enabled
    ) {
        public Definition {
            if (aliases == null) aliases = List.of();
            aliases = List.copyOf(aliases);
            if (template == null) template = "";
        }

        /** True when this command must not be shown to anyone but the local player or an admin. */
        public boolean privateOutput() {
            return sensitive;
        }
    }

    /**
     * Every status command, in the order the settings screen lists them.
     *
     * <p>The order runs from the broadest overview to the most personal figure, so the sensitive
     * entries sit together at the end and are easy to audit.
     */
    private static final List<Definition> COMMANDS = List.of(
        new Definition("status", List.of("overview"),
            "A short overview of the server and your connection.",
            "Server: {server} | TPS: {tps} | Ping: {ping}ms", false, true),
        new Definition("tps", List.of("tickrate"),
            "The server's tick rate and milliseconds per tick.",
            "TPS: {tps}/20 | MSPT: {mspt}ms", false, true),
        new Definition("ping", List.of("latency", "ms"),
            "Your round-trip latency and a verdict.",
            "Ping: {ping}ms ({verdict})", false, true),
        new Definition("players", List.of("online", "playercount"),
            "How many players are visible, and the maximum when known.",
            "Online: {playercount}/{maxplayers}", false, true),
        new Definition("uptime", List.of("session"),
            "How long this session has been connected.",
            "Session: {uptime}", false, true),
        new Definition("network", List.of("connection"),
            "Connection state, latency and the server address.",
            "{connection} | Ping: {ping}ms | {address}", true, true),
        new Definition("server", List.of("serverinfo", "host"),
            "Detailed server information.",
            "Server: {server} | Players: {playercount}/{maxplayers} | Uptime: {uptime}",
            false, true),
        new Definition("fps", List.of("frames"),
            "The client's frame rate.",
            "FPS: {fps}", false, true),
        new Definition("memory", List.of("ram"),
            "JVM memory in use by the client.",
            "Memory: {memory}", false, true),
        new Definition("speed", List.of("velocity"),
            "Your horizontal movement speed.",
            "Speed: {speed} blocks/s", true, true),
        new Definition("world", List.of("dimension"),
            "The world and dimension you are in.",
            "World: {world} ({dimension})", false, true),
        new Definition("time", List.of("clock"),
            "The in-game clock.",
            "Time: {worldtime}", false, true),
        new Definition("day", List.of("daycount"),
            "The current world day.",
            "Day: {day}", false, true),
        new Definition("weather", List.of("wx"),
            "The current world weather.",
            "Weather: {weather}", false, true),
        new Definition("gamemode", List.of("gm"),
            "Your current game mode.",
            "Gamemode: {gamemode}", true, true),
        new Definition("health", List.of("hp"),
            "Your health, absorption and hunger.",
            "Health: {health} | Hunger: {hunger}", true, true),
        new Definition("absorption", List.of("absorb"),
            "The absorption hearts you are carrying.",
            "Absorption: {absorption}", true, true),
        new Definition("level", List.of("xp", "lvl"),
            "Your experience level.",
            "Level: {level}", true, true),
        new Definition("difficulty", List.of("diff"),
            "The world difficulty, as the client can see it.",
            "Difficulty: {difficulty}", false, true),
        new Definition("effects", List.of("potions"),
            "Your active status effects.",
            "Effects: {effects}", true, true),
        new Definition("armor", List.of("gear"),
            "Your equipped armour.",
            "Armor: {armor}", true, true),
        new Definition("held", List.of("hand", "item"),
            "The item in each hand.",
            "Held: {held} | Offhand: {offhand}", true, true),
        new Definition("coords", List.of("pos", "position"),
            "Your coordinates.",
            "XYZ: {x}, {y}, {z}", true, true),
        new Definition("location", List.of("where"),
            "Your biome, dimension, coordinates and facing.",
            "XYZ: {x}, {y}, {z} | {dimension} | {biome} | {facing}", true, true),
        new Definition("target", List.of("looking"),
            "The block or entity you are looking at.",
            "Target: {target}", true, true)
    );

    /** Canonical name and alias to canonical name, built once. */
    private static final Map<String, String> ALIASES = buildAliases();

    private StatusCommand() {
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Definition command : COMMANDS) {
            map.put(command.name(), command.name());
            for (String alias : command.aliases()) {
                map.putIfAbsent(alias.toLowerCase(Locale.ROOT), command.name());
            }
        }
        return Map.copyOf(map);
    }

    /** Every command, in display order. */
    public static List<Definition> all() {
        return COMMANDS;
    }

    /** Every canonical name, in display order. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(COMMANDS.size());
        for (Definition command : COMMANDS) out.add(command.name());
        return List.copyOf(out);
    }

    /** The alias table, for resolving a typed verb to its canonical name. */
    public static Map<String, String> aliases() {
        return ALIASES;
    }

    /** The canonical name for {@code word}, or empty when it is not a status command. */
    public static Optional<String> canonical(String word) {
        if (word == null) return Optional.empty();
        return Optional.ofNullable(ALIASES.get(word.trim().toLowerCase(Locale.ROOT)));
    }

    /** The definition of {@code command}, matched on its canonical name. */
    public static Optional<Definition> find(String command) {
        if (command == null) return Optional.empty();
        String key = command.trim().toLowerCase(Locale.ROOT);
        for (Definition candidate : COMMANDS) {
            if (candidate.name().equals(key)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** The default template for {@code command}, or empty when the command is unknown. */
    public static Optional<String> defaultTemplate(String command) {
        return find(command).map(Definition::template);
    }

    /** The commands that expose the player rather than the server. */
    public static List<Definition> sensitive() {
        List<Definition> out = new ArrayList<>();
        for (Definition command : COMMANDS) if (command.sensitive()) out.add(command);
        return List.copyOf(out);
    }

    /** The default template for every command, as a name-to-template map. */
    public static Map<String, String> defaultTemplates() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Definition command : COMMANDS) out.put(command.name(), command.template());
        return Map.copyOf(out);
    }
}
