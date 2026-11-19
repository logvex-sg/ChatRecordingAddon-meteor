package com.logvex.addon.stats;

import java.util.Locale;
import java.util.Optional;

/**
 * Answers the chat commands that report live server and player figures.
 *
 * <p>Kept separate from {@link com.logvex.addon.assistant.core.AssistantBrain} so the command table
 * and the wording are testable with no Minecraft types, and so a new figure is one entry here rather
 * than a new branch in the brain. The brain owns the decision record; this class only decides which
 * text answers a verb.
 *
 * <p>Every command is individually switchable. A command that is turned off returns empty, so the
 * assistant falls through to its other sources (memory commands, the local AI) exactly as if the
 * verb had never existed.
 */
public final class StatsCommands {
    /** Which commands may answer. */
    public record Options(
        boolean enabled,
        String ping,
        String tps,
        String stats,
        String coords,
        String health,
        String fps,
        String server,
        String time,
        ServerStats.Detail detail,
        String separator
    ) {
        public static final Options DEFAULTS = new Options(
            true, "ping", "tps", "stats", "coords", "health", "fps", "server", "time",
            ServerStats.Detail.BRIEF, " | ");

        public Options {
            if (ping == null || ping.isBlank()) ping = "ping";
            if (tps == null || tps.isBlank()) tps = "tps";
            if (stats == null || stats.isBlank()) stats = "stats";
            if (coords == null || coords.isBlank()) coords = "coords";
            if (health == null || health.isBlank()) health = "health";
            if (fps == null || fps.isBlank()) fps = "fps";
            if (server == null || server.isBlank()) server = "server";
            if (time == null || time.isBlank()) time = "time";
            if (detail == null) detail = ServerStats.Detail.BRIEF;
            if (separator == null) separator = " | ";
        }
    }

    private StatsCommands() {
    }

    /**
     * The answer to {@code body} (the text after the command prefix), or empty when no stats command
     * matches or the matching one is switched off.
     */
    public static Optional<String> answer(String body, ServerStats stats, Options options) {
        if (body == null || body.isBlank()) return Optional.empty();
        Options opts = options == null ? Options.DEFAULTS : options;
        if (!opts.enabled()) return Optional.empty();
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;

        String verb = body.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (matches(verb, opts.ping())) return Optional.of(ping(s));
        if (matches(verb, opts.tps())) return Optional.of(tps(s));
        if (matches(verb, opts.stats())) return Optional.of(stats(s, opts));
        if (matches(verb, opts.coords())) return Optional.of(coords(s));
        if (matches(verb, opts.health())) return Optional.of(health(s));
        if (matches(verb, opts.fps())) return Optional.of(fps(s));
        if (matches(verb, opts.server())) return Optional.of(server(s));
        if (matches(verb, opts.time())) return Optional.of(time(s));
        return Optional.empty();
    }

    /** The verbs this class can answer, for the assistant's help text. */
    public static String usage(Options options) {
        Options o = options == null ? Options.DEFAULTS : options;
        return o.ping() + ", " + o.tps() + ", " + o.stats() + ", " + o.coords() + ", "
            + o.health() + ", " + o.fps() + ", " + o.server() + ", " + o.time();
    }

    private static boolean matches(String verb, String name) {
        return verb.equalsIgnoreCase(name.trim());
    }

    /** Latency and a plain-language verdict. */
    public static String ping(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        if (s.latency() < 0) return "Ping is not available yet.";
        return "Ping " + s.latency() + " ms (" + s.verdict() + ").";
    }

    /** The server's tick rate, with a warning when it is behind. */
    public static String tps(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        if (s.tps() <= 0f) return "TPS is not available yet.";
        String base = "TPS " + ServerStats.formatTps(s.tps()) + " / 20";
        if (s.lagging()) return base + " (lagging).";
        return base + ".";
    }

    /** The full set of figures, in the requested detail. */
    public static String stats(ServerStats s, Options options) {
        if (!s.inWorld()) return "Not connected.";
        Options o = options == null ? Options.DEFAULTS : options;
        return s.join(o.detail(), o.separator()) + ".";
    }

    /** The local player's position, rounded to whole blocks. */
    public static String coords(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return String.format(Locale.ROOT, "XYZ %.0f %.0f %.0f (%s).",
            s.x(), s.y(), s.z(), s.dimension().isBlank() ? "?" : s.dimension());
    }

    /** The local player's health and hunger. */
    public static String health(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return String.format(Locale.ROOT, "Health %.1f/%.0f, food %d.",
            s.health(), s.maxHealth(), s.food());
    }

    /** The client's own frame rate. */
    public static String fps(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return s.fps() + " FPS.";
    }

    /** The server name and the session length. */
    public static String server(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        String name = s.server().isBlank() ? "single-player" : s.server();
        return name + ", up " + ServerStats.formatUptime(s.uptimeMs()) + ".";
    }

    /** The in-game clock. */
    public static String time(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return "World time " + ServerStats.formatWorldTime(s.worldTime()) + ".";
    }
}
