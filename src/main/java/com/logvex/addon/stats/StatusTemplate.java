package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands a status template such as {@code "TPS: {tps} | Ping: {ping}ms"}.
 *
 * <p>Two rules keep the output honest. A figure the client cannot observe resolves to {@code n/a}
 * rather than to zero or to a blank, so a template never silently reads as a real measurement. And
 * an unknown token is left in the text verbatim, so a typo is visible instead of being erased.
 *
 * <p>The values themselves come from {@link ServerStats}, which is a plain record: this class needs
 * no game and is therefore fully unit-testable.
 */
public final class StatusTemplate {
    /** What an unavailable figure renders as. */
    public static final String UNAVAILABLE = "n/a";

    /**
     * One placeholder: its name, a sample value for the reference list, and a note on its source.
     *
     * @param name    the token to write, without braces
     * @param sample  a realistic example, for the placeholder reference in the settings screen
     * @param origin  where the value comes from, so a user can tell a guess from a fact
     * @param detail  what it means, in one short sentence
     */
    public record Placeholder(String name, String sample, ServerStats.Origin origin, String detail) {
    }

    /**
     * What a template is expanded against.
     *
     * @param requester     the name of the player who asked, for {@code {player}}
     * @param requesterPing that player's latency, or a negative value when it could not be read
     *
     * <p>The requester's own latency is carried separately from {@link ServerStats#latency()}
     * because the tab list holds every visible player's figure: {@code !ping} from another player
     * must answer with <em>their</em> number, and attributing this client's latency to them would be
     * a fabricated measurement.
     */
    public record Context(String requester, int requesterPing, String assistant, String prefix) {
        public static final Context EMPTY = new Context("", -1, "", "");

        public Context {
            if (requester == null) requester = "";
            if (assistant == null) assistant = "";
            if (prefix == null) prefix = "";
        }

        /** A context that knows only the requester, as a caller with no config would have. */
        public Context(String requester, int requesterPing) {
            this(requester, requesterPing, "", "");
        }

        /** The same context carrying the assistant's identity, for {@code {name}} and {@code {prefix}}. */
        public Context withIdentity(String assistant, String prefix) {
            return new Context(requester, requesterPing, assistant, prefix);
        }
    }

    private StatusTemplate() {
    }

    /** The placeholders every template may use, in the order the reference lists them. */
    public static List<Placeholder> placeholders() {
        List<Placeholder> out = new ArrayList<>();
        out.add(new Placeholder("server", "play.example.net", ServerStats.Origin.SERVER,
            "The server name, or single-player."));
        out.add(new Placeholder("address", "play.example.net:25565", ServerStats.Origin.SERVER,
            "The server address as configured."));
        out.add(new Placeholder("connection", "connected", ServerStats.Origin.CLIENT,
            "connected, single-player or offline."));
        out.add(new Placeholder("tps", "20.0", ServerStats.Origin.OBSERVED,
            "Tick rate measured from keep-alive spacing."));
        out.add(new Placeholder("mspt", "50.0", ServerStats.Origin.OBSERVED,
            "Milliseconds per tick, derived from the tick rate."));
        out.add(new Placeholder("ping", "42", ServerStats.Origin.OBSERVED,
            "Your latency in milliseconds."));
        out.add(new Placeholder("verdict", "good", ServerStats.Origin.OBSERVED,
            "A word for the connection: good, fair, poor, lagging."));
        out.add(new Placeholder("playercount", "12", ServerStats.Origin.SERVER,
            "Players visible in the tab list."));
        out.add(new Placeholder("maxplayers", "100", ServerStats.Origin.SERVER,
            "The slot maximum, when the server tells the client."));
        out.add(new Placeholder("uptime", "1h 12m", ServerStats.Origin.CLIENT,
            "How long this session has been connected."));
        out.add(new Placeholder("dimension", "minecraft:overworld", ServerStats.Origin.SERVER,
            "The dimension id you are in."));
        out.add(new Placeholder("world", "overworld", ServerStats.Origin.SERVER,
            "The short world name."));
        out.add(new Placeholder("biome", "plains", ServerStats.Origin.SERVER,
            "The biome at your feet, when the chunk is loaded."));
        out.add(new Placeholder("worldtime", "06:00 day", ServerStats.Origin.SERVER,
            "The in-game clock."));
        out.add(new Placeholder("day", "128", ServerStats.Origin.SERVER,
            "The world day count."));
        out.add(new Placeholder("weather", "clear", ServerStats.Origin.SERVER,
            "clear, rain or thunder."));
        out.add(new Placeholder("x", "128", ServerStats.Origin.CLIENT, "Your X coordinate."));
        out.add(new Placeholder("y", "64", ServerStats.Origin.CLIENT, "Your Y coordinate."));
        out.add(new Placeholder("z", "-32", ServerStats.Origin.CLIENT, "Your Z coordinate."));
        out.add(new Placeholder("coords", "128, 64, -32", ServerStats.Origin.CLIENT,
            "All three coordinates."));
        out.add(new Placeholder("facing", "north", ServerStats.Origin.CLIENT,
            "The compass direction you face."));
        out.add(new Placeholder("health", "18.5/20", ServerStats.Origin.CLIENT,
            "Your health, with absorption when you have any."));
        out.add(new Placeholder("hunger", "17", ServerStats.Origin.CLIENT,
            "Your food level."));
        out.add(new Placeholder("gamemode", "survival", ServerStats.Origin.SERVER,
            "The game mode the server put you in."));
        out.add(new Placeholder("speed", "4.32", ServerStats.Origin.CLIENT,
            "Your measured horizontal speed."));
        out.add(new Placeholder("fps", "144", ServerStats.Origin.CLIENT,
            "The client's frame rate."));
        out.add(new Placeholder("memory", "412/4096 MB", ServerStats.Origin.CLIENT,
            "JVM memory in use."));
        out.add(new Placeholder("armor", "Head: Netherite Helmet", ServerStats.Origin.CLIENT,
            "Worn armour, one slot per item."));
        out.add(new Placeholder("held", "Diamond Sword", ServerStats.Origin.CLIENT,
            "The item in your main hand."));
        out.add(new Placeholder("offhand", "Shield", ServerStats.Origin.CLIENT,
            "The item in your off hand."));
        out.add(new Placeholder("effects", "Speed II (2m 10s)", ServerStats.Origin.CLIENT,
            "Active status effects."));
        out.add(new Placeholder("target", "Stone @ 12, 64, -8", ServerStats.Origin.CLIENT,
            "The block or entity under your crosshair."));
        out.add(new Placeholder("player", "Steve", ServerStats.Origin.CLIENT,
            "The name of whoever asked."));
        out.add(new Placeholder("name", "Assistant", ServerStats.Origin.CLIENT,
            "The assistant's own name."));
        out.add(new Placeholder("prefix", "!", ServerStats.Origin.CLIENT,
            "The command prefix, so a template can name another command."));
        return List.copyOf(out);
    }

    /** The placeholders as a name-to-detail map, for a compact reference list. */
    public static Map<String, String> reference() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Placeholder placeholder : placeholders()) {
            out.put(placeholder.name(), placeholder.detail());
        }
        return Map.copyOf(out);
    }

    /**
     * Expands {@code template} against {@code stats}.
     *
     * @param requester the name of the player who asked, for {@code {player}}; may be blank
     * @return the expanded text, with unknown tokens left as written
     */
    public static String expand(String template, ServerStats stats, String requester) {
        return expand(template, stats, new Context(requester, -1));
    }

    /** Expands {@code template} against {@code stats} and the requester's own figures. */
    public static String expand(String template, ServerStats stats, Context context) {
        if (template == null || template.isEmpty()) return "";
        Context ctx = context == null ? Context.EMPTY : context;
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
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
                out.append(template, i, template.length());
                break;
            }
            String token = template.substring(i + 1, close).trim();
            String value = value(token, s, ctx);
            if (value == null) {
                // Unknown placeholder: keep it verbatim so a typo is visible, not erased.
                out.append(template, i, close + 1);
            } else {
                out.append(value);
                // `{ping}ms` must not become `n/ams`, which reads as a unit of measurement.
                if (UNAVAILABLE.equals(value) && close + 1 < template.length()
                        && Character.isLetterOrDigit(template.charAt(close + 1))) {
                    out.append(' ');
                }
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** The replacement for one token, or null when no placeholder claims it. */
    public static String value(String token, ServerStats stats, String requester) {
        return value(token, stats, new Context(requester, -1));
    }

    /** The replacement for one token, or null when no placeholder claims it. */
    public static String value(String token, ServerStats stats, Context context) {
        if (token == null || token.isBlank()) return null;
        Context ctx = context == null ? Context.EMPTY : context;
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "server" -> s.serverLabel();
            case "address" -> orNa(s.serverAddress());
            case "connection" -> connection(s);
            case "tps" -> s.tps() <= 0f ? UNAVAILABLE : ServerStats.formatTps(s.tps());
            case "mspt" -> s.mspt() < 0f
                ? UNAVAILABLE : String.format(Locale.ROOT, "%.1f", s.mspt());
            case "ping" -> ping(s, ctx);
            case "verdict" -> s.offline() ? "offline" : s.verdict();
            case "playercount" -> s.inWorld() ? String.valueOf(s.players()) : UNAVAILABLE;
            case "maxplayers" -> s.hasMaxPlayers()
                ? String.valueOf(s.maxPlayers()) : UNAVAILABLE;
            case "uptime" -> s.uptimeMs() <= 0L ? UNAVAILABLE : ServerStats.formatUptime(s.uptimeMs());
            case "dimension" -> orNa(s.dimension());
            case "world" -> orNa(s.world());
            case "biome" -> orNa(s.biome());
            case "worldtime" -> s.inWorld() ? ServerStats.formatWorldTime(s.worldTime()) : UNAVAILABLE;
            case "day" -> s.dayLabel();
            case "weather" -> s.weatherLabel();
            case "x" -> coord(s, s.x());
            case "y" -> coord(s, s.y());
            case "z" -> coord(s, s.z());
            case "coords" -> s.player().absent() ? UNAVAILABLE : s.player().coordsLabel();
            case "facing" -> orNa(s.facing());
            case "health" -> s.player().absent() ? UNAVAILABLE : s.player().healthLabel();
            case "hunger" -> s.player().absent() ? UNAVAILABLE : String.valueOf(s.food());
            case "gamemode" -> s.player().gamemodeLabel();
            case "speed" -> s.player().absent()
                ? UNAVAILABLE : String.format(Locale.ROOT, "%.2f", s.speed());
            case "fps" -> s.offline() ? UNAVAILABLE : String.valueOf(s.fps());
            case "memory" -> s.offline() ? UNAVAILABLE : memory(s);
            case "armor" -> joinOrNa(s.player().wornArmor(), ", ");
            case "held" -> orNa(s.player().heldItem());
            case "offhand" -> orNa(s.player().offhandItem());
            case "effects" -> joinOrNa(s.player().effects(), ", ");
            case "target" -> s.player().targetLabel();
            case "player" -> ctx.requester().isBlank() ? UNAVAILABLE : ctx.requester();
            case "name" -> ctx.assistant().isBlank() ? "the assistant" : ctx.assistant();
            case "prefix" -> ctx.prefix().isBlank() ? "!" : ctx.prefix();
            default -> null;
        };
    }

    /**
     * The latency to show for {@code {ping}}.
     *
     * <p>The requester's own figure wins when it could be read. It falls back to the client's own
     * reading only when nobody in particular asked, which is the case for a preview or a local
     * readout by the local player.
     */
    private static String ping(ServerStats s, Context ctx) {
        int latency = ctx.requesterPing() >= 0 ? ctx.requesterPing() : s.latency();
        return latency < 0 ? UNAVAILABLE : String.valueOf(latency);
    }

    /** {@code connected}, {@code single-player} or {@code offline}. */
    public static String connection(ServerStats stats) {
        if (stats == null || stats.offline()) return "offline";
        return stats.singleplayer() ? "single-player" : "connected";
    }

    private static String memory(ServerStats s) {
        if (s.maxMemoryMb() > 0L) return s.usedMemoryMb() + "/" + s.maxMemoryMb() + " MB";
        return s.usedMemoryMb() + " MB";
    }

    private static String coord(ServerStats s, double value) {
        if (s.player().absent()) return UNAVAILABLE;
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String orNa(String value) {
        return value == null || value.isBlank() ? UNAVAILABLE : value;
    }

    private static String joinOrNa(List<String> values, String separator) {
        if (values == null || values.isEmpty()) return UNAVAILABLE;
        return String.join(separator, values);
    }

    /**
     * True when every token in {@code template} is one this class knows.
     *
     * <p>Used by the settings screen to flag a typo before it reaches chat, and by the tests that
     * assert the shipped templates are all valid.
     */
    public static boolean isValid(String template) {
        if (template == null || template.isEmpty()) return true;
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) return true;
            int close = template.indexOf('}', open + 1);
            if (close < 0) return false;
            String token = template.substring(open + 1, close).trim();
            if (value(token, ServerStats.OFFLINE, Context.EMPTY) == null) return false;
            i = close + 1;
        }
        return true;
    }

    /** Every unknown token in {@code template}, in the order they appear. */
    public static List<String> unknownTokens(String template) {
        List<String> out = new ArrayList<>();
        if (template == null || template.isEmpty()) return List.copyOf(out);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) break;
            int close = template.indexOf('}', open + 1);
            if (close < 0) break;
            String token = template.substring(open + 1, close).trim();
            if (value(token, ServerStats.OFFLINE, Context.EMPTY) == null && !out.contains(token)) {
                out.add(token);
            }
            i = close + 1;
        }
        return List.copyOf(out);
    }
}
